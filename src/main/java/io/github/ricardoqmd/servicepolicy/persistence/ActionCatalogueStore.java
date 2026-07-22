package io.github.ricardoqmd.servicepolicy.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Singleton;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.problem.ActionInUseException;
import io.github.ricardoqmd.servicepolicy.problem.CatalogueEntryAlreadyExistsException;
import io.github.ricardoqmd.servicepolicy.problem.CatalogueEntryNotFoundException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionFailedException;
import io.github.ricardoqmd.servicepolicy.problem.ProblemException;

/**
 * The only door to the action catalogue collection (ADR-028): REST reads and writes entries through
 * this store, never through {@link ActionCatalogueRepository} directly, keeping the web layer off
 * Panache exactly as {@link PolicyLifecycleStore} does for policies.
 *
 * <p>Writes follow the conditional-write invariants of ADR-018/ADR-019: a single-document CAS on
 * {@code (app, resourceType, revision)} is the commit point, and a CAS that matches nothing is
 * disambiguated into 412 (stale) or 404 (missing). Create relies on the unique index rather than a
 * pre-check, so two concurrent creates cannot both succeed.
 *
 * <p><strong>Removal is guarded, not silent.</strong> An action still referenced by an active policy
 * of the same {@code (app, resourceType)} blocks the write with {@code ACTION_IN_USE} — the safe
 * default ADR-028 leaves in place until a deprecation contract is decided. The check reads the
 * active policies and then writes, so a policy activated in between is not seen. That race is
 * accepted deliberately: this is a single-instance deployment administered by humans at
 * human cadence, and the failure mode is bounded on the enforcement side — the worst outcome is a
 * catalogue that no longer lists an action some active policy still names, which makes that policy
 * un-reauthorable and the action un-enumerable, but changes no decision. It can never over-permit,
 * because {@code /evaluate} does not read the catalogue at all.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class ActionCatalogueStore {

    private static final int DUPLICATE_KEY_CODE = 11000;

    private static final String APP = "app";
    private static final String RESOURCE_TYPE = "resourceType";
    private static final String ACTIONS = "actions";
    private static final String REVISION = "revision";
    private static final String AUDIT = "audit";
    private static final String CREATED_BY = "createdBy";
    private static final String CREATED_AT = "createdAt";

    private final ActionCatalogueRepository repository;
    private final PolicyLifecycleStore policyStore;

    ActionCatalogueStore(ActionCatalogueRepository repository, PolicyLifecycleStore policyStore) {
        this.repository = repository;
        this.policyStore = policyStore;
    }

    /** @return the entry for {@code (app, resourceType)}, if the app declares that resource type. */
    public Optional<ActionCatalogueEntry> find(String app, String resourceType) {
        return repository.findByAppAndResourceType(app, resourceType).map(ActionCatalogueStore::entry);
    }

    /**
     * @return every entry of the app, ordered by resource type. Not paged by design (ADR-028): a
     *     catalogue is a bounded vocabulary — tens of entries, not thousands — and its consumers
     *     need it whole.
     */
    public List<ActionCatalogueEntry> list(String app) {
        return repository.findByApp(app).stream()
                .map(ActionCatalogueStore::entry)
                .toList();
    }

    /**
     * Declares the vocabulary of a resource type in an application, at {@code revision = 1}.
     *
     * @throws CatalogueEntryAlreadyExistsException (409) if the app already declares that resource
     *     type — arbitrated by the unique index, so a concurrent create loses rather than duplicates.
     */
    public ActionCatalogueEntry create(String app, String resourceType, List<String> actions, String callerSubject) {
        ActionCatalogueDocument document = new ActionCatalogueDocument();
        document.app = app;
        document.resourceType = resourceType;
        document.actions = List.copyOf(actions);
        document.revision = 1L;
        document.audit = auditDocument(callerSubject);

        try {
            repository.mongoCollection().insertOne(document);
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_CODE) {
                throw new CatalogueEntryAlreadyExistsException(app, resourceType);
            }
            throw e;
        }
        return entry(document);
    }

    /**
     * Replaces the entry's full action set (ADR-028). Adding actions is always safe — it changes no
     * existing policy, since {@code '*'} was already expanded at authoring. Removing one is not, so
     * every action being dropped is checked against the app's active policies first.
     *
     * @param ifMatch the revision value from the client's {@code If-Match} header.
     * @throws CatalogueEntryNotFoundException (404) if the app does not declare that resource type.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     * @throws ActionInUseException (409) if a removed action is referenced by an active policy.
     */
    public ActionCatalogueEntry replace(
            String app, String resourceType, List<String> actions, long ifMatch, String callerSubject) {
        ActionCatalogueDocument current = requirePrecondition(app, resourceType, ifMatch);

        List<String> removed = current.actions.stream()
                .filter(action -> !actions.contains(action))
                .toList();
        rejectIfInUse(app, resourceType, removed);

        UpdateResult cas = repository
                .mongoCollection()
                .updateOne(
                        Filters.and(identity(app, resourceType), Filters.eq(REVISION, ifMatch)),
                        Updates.combine(
                                Updates.set(ACTIONS, List.copyOf(actions)),
                                Updates.set(AUDIT, auditDocument(callerSubject)),
                                Updates.inc(REVISION, 1L)));

        if (cas.getMatchedCount() == 0) {
            throw staleOrMissing(app, resourceType);
        }
        return find(app, resourceType).orElseThrow(() -> new CatalogueEntryNotFoundException(app, resourceType));
    }

    /**
     * Deletes the entry, undeclaring the resource type's vocabulary. Blocked while <em>any</em> of
     * its actions is referenced by an active policy: deleting the entry is removing every action at
     * once, so it is guarded the same way a partial removal is.
     *
     * @param ifMatch the revision value from the client's {@code If-Match} header.
     * @throws CatalogueEntryNotFoundException (404) if the app does not declare that resource type.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     * @throws ActionInUseException (409) if any action is referenced by an active policy.
     */
    public void delete(String app, String resourceType, long ifMatch) {
        ActionCatalogueDocument current = requirePrecondition(app, resourceType, ifMatch);

        rejectIfInUse(app, resourceType, current.actions);

        DeleteResult result = repository
                .mongoCollection()
                .deleteOne(Filters.and(identity(app, resourceType), Filters.eq(REVISION, ifMatch)));

        if (result.getDeletedCount() == 0) {
            throw staleOrMissing(app, resourceType);
        }
    }

    /**
     * Loads the entry and checks {@code If-Match} <em>before</em> the conditional write is evaluated
     * any further, so a stale precondition is answered with 412 rather than with whatever the method
     * would otherwise have objected to. RFC 9110 §13.1 puts preconditions ahead of method semantics
     * for exactly this reason: a client holding a stale ETag has an out-of-date picture, and telling
     * it "this action is in use" invites it to act on that picture instead of reloading. A 412 says
     * the only true thing — refresh and look again.
     *
     * <p>Best-effort by construction: this is a read, so another writer can still slip in before the
     * CAS below. The CAS remains the authority — it is the commit point, and a precondition that was
     * true here but false there still fails there (ADR-018/ADR-019).
     *
     * @throws CatalogueEntryNotFoundException (404) if the app does not declare that resource type.
     * @throws PreconditionFailedException (412) if {@code ifMatch} does not match the current revision.
     */
    private ActionCatalogueDocument requirePrecondition(String app, String resourceType, long ifMatch) {
        ActionCatalogueDocument current = repository
                .findByAppAndResourceType(app, resourceType)
                .orElseThrow(() -> new CatalogueEntryNotFoundException(app, resourceType));
        if (current.revision != ifMatch) {
            throw PreconditionFailedException.forCatalogueEntry(app, resourceType, current.revision);
        }
        return current;
    }

    /**
     * Rejects the write when any of {@code actions} is named by an active policy of the same
     * {@code (app, resourceType)}. Inactive policies never block: they decide nothing, and version
     * history is immutable anyway — re-activating one after its verb was removed is a decision an
     * administrator takes explicitly.
     */
    private void rejectIfInUse(String app, String resourceType, List<String> actions) {
        if (actions.isEmpty()) {
            return;
        }
        Set<String> blocking = new LinkedHashSet<>();
        List<String> policyIds = new ArrayList<>();
        for (Policy policy : policyStore.activePoliciesFor(app, resourceType)) {
            List<String> referenced =
                    actions.stream().filter(policy.actions()::contains).toList();
            if (!referenced.isEmpty()) {
                blocking.addAll(referenced);
                policyIds.add(policy.id());
            }
        }
        if (!blocking.isEmpty()) {
            throw new ActionInUseException(app, resourceType, List.copyOf(blocking), policyIds);
        }
    }

    /** The composite-identity filter every catalogue write CASes against (ADR-026, ADR-028). */
    private static Bson identity(String app, String resourceType) {
        return Filters.and(Filters.eq(APP, app), Filters.eq(RESOURCE_TYPE, resourceType));
    }

    /**
     * A CAS that matched nothing is either a stale If-Match on an existing entry (412) or an entry
     * that does not exist in this app (404) — the same disambiguation the policy store performs.
     *
     * <p><strong>Reachable only under concurrency, and deliberately untested.</strong>
     * {@link #requirePrecondition} has already read the entry and checked the revision, so
     * single-threaded this branch cannot fire: the only way to get here is for another writer to
     * replace or delete the entry between that read and this CAS. Keeping it is not defensive
     * clutter — the CAS is the commit point (ADR-018/ADR-019) and the precondition check above is
     * merely a courtesy that answers stale clients with the right status; this resolution is what
     * makes the commit point's own verdict safe. It stays uncovered because reproducing it would
     * mean orchestrating a write between two statements of this method, which buys a covered line
     * and no confidence.
     */
    private ProblemException staleOrMissing(String app, String resourceType) {
        return find(app, resourceType)
                .map(existing -> (ProblemException)
                        PreconditionFailedException.forCatalogueEntry(app, resourceType, existing.revision()))
                .orElseGet(() -> new CatalogueEntryNotFoundException(app, resourceType));
    }

    /** Same field names and shape as the head audit; the catalogue has no changeReason concept. */
    private static Document auditDocument(String callerSubject) {
        return new Document(CREATED_BY, callerSubject)
                .append(CREATED_AT, Instant.now().toString());
    }

    private static ActionCatalogueEntry entry(ActionCatalogueDocument document) {
        return new ActionCatalogueEntry(document.app, document.resourceType, document.actions, document.revision);
    }
}
