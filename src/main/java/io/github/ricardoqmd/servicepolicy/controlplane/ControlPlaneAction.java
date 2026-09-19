package io.github.ricardoqmd.servicepolicy.controlplane;

/**
 * The four actions of the control plane (ADR-033 §1), over the resource type {@value #RESOURCE_TYPE}.
 *
 * <p>There is no fifth. Declaring on whose behalf a write is made is not an action and is not gated
 * (ADR-033 §4); a finer grant is an additional policy over these same four.
 */
public enum ControlPlaneAction {
    /** Any control-plane read, and simulation, which reads and writes nothing else. */
    READ("read"),
    /** Authoring a policy version, and writing an application's catalogue or configuration. */
    WRITE("write"),
    ACTIVATE("activate"),
    DEACTIVATE("deactivate");

    /** The resource type control-plane policies govern. */
    public static final String RESOURCE_TYPE = "policy";

    private final String verb;

    ControlPlaneAction(String verb) {
        this.verb = verb;
    }

    /** @return the verb as a policy lists it in {@code actions} and as the catalogue declares it. */
    public String verb() {
        return verb;
    }

    /** @return the action in {@code type:verb} form, as a decision request carries it. */
    public String action() {
        return RESOURCE_TYPE + ":" + verb;
    }
}
