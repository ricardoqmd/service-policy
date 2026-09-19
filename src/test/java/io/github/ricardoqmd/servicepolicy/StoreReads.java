package io.github.ricardoqmd.servicepolicy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Singleton;

import org.bson.BsonDocument;
import org.bson.BsonString;

import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;

/**
 * Records every read the service sends to MongoDB, so a test can say which application's data a request
 * touched.
 *
 * <p>Quarkus registers every {@link CommandListener} bean on the client it builds, so this sees the
 * commands the service itself issues — not a copy of the store's API that production code might bypass.
 * A read is kept with the {@code app} its filter names, which is how every store in this service scopes
 * a query (ADR-026); a read without one is kept with {@code null}.
 */
@Singleton
public class StoreReads implements CommandListener {

    private static final List<String> READS = List.of("find", "aggregate", "count", "countDocuments", "distinct");

    /** One read: the command, the collection it read, and the application its filter named. */
    public record Read(String command, String collection, String app) {}

    private final List<Read> reads = new CopyOnWriteArrayList<>();

    @Override
    public void commandStarted(CommandStartedEvent event) {
        String command = event.getCommandName();
        if (!READS.contains(command)) {
            return;
        }
        BsonDocument body = event.getCommand();
        String collection = body.isString(command) ? body.getString(command).getValue() : null;
        BsonDocument filter = body.isDocument("filter") ? body.getDocument("filter") : new BsonDocument();
        String app = filter.get("app") instanceof BsonString value ? value.getValue() : null;
        reads.add(new Read(command, collection, app));
    }

    /** Forgets everything recorded so far. */
    public void clear() {
        reads.clear();
    }

    /** @return the reads recorded since the last {@link #clear()} whose filter named {@code app}. */
    public List<Read> scopedTo(String app) {
        return reads.stream().filter(read -> app.equals(read.app())).toList();
    }
}
