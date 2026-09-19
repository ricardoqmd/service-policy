package io.github.ricardoqmd.servicepolicy.controlplane;

import java.util.Optional;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;

/** A control-plane configuration built by hand, for the suites that call startup with the values each case needs. */
final class ControlPlaneConfigs {

    private ControlPlaneConfigs() {
        // static helper
    }

    static ServicePolicyConfig config(Optional<String> appsClaim, Optional<String> bootstrapValue, String reservedApp) {
        ServicePolicyConfig.ControlPlane controlPlane = new ServicePolicyConfig.ControlPlane() {
            @Override
            public String reservedApp() {
                return reservedApp;
            }

            @Override
            public ServicePolicyConfig.SubjectAttributes subjectAttributes() {
                return () -> appsClaim;
            }

            @Override
            public ServicePolicyConfig.Bootstrap bootstrap() {
                return new ServicePolicyConfig.Bootstrap() {
                    @Override
                    public String claim() {
                        return "sub";
                    }

                    @Override
                    public Optional<String> value() {
                        return bootstrapValue;
                    }
                };
            }
        };
        return new ServicePolicyConfig() {
            @Override
            public Info info() {
                return null;
            }

            @Override
            public Authz authz() {
                return null;
            }

            @Override
            public Evaluation evaluation() {
                return null;
            }

            @Override
            public ControlPlane controlPlane() {
                return controlPlane;
            }
        };
    }
}
