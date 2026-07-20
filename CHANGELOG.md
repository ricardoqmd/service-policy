# Changelog

## [0.3.4](https://github.com/ricardoqmd/service-policy/compare/v0.3.3...v0.3.4) (2026-07-20)


### Documentation

* add ADR-028 action catalogue and wildcard expansion ([#142](https://github.com/ricardoqmd/service-policy/issues/142)) ([27df257](https://github.com/ricardoqmd/service-policy/commit/27df257abbb1ffb00f80f2fccd449e9dccb8b58e))

## [0.3.3](https://github.com/ricardoqmd/service-policy/compare/v0.3.2...v0.3.3) (2026-07-16)


### Features

* add admin-gated policy simulation endpoint ([#139](https://github.com/ricardoqmd/service-policy/issues/139)) ([03de388](https://github.com/ricardoqmd/service-policy/commit/03de38884cc29df0bb4af2563d3b9a8512b6a3a8))

## [0.3.2](https://github.com/ricardoqmd/service-policy/compare/v0.3.1...v0.3.2) (2026-07-15)


### Documentation

* add ADR-027 policy simulation ([#136](https://github.com/ricardoqmd/service-policy/issues/136)) ([a542ed5](https://github.com/ricardoqmd/service-policy/commit/a542ed50401c6dd4d0180bdf9832584eccf166ed))

## [0.3.1](https://github.com/ricardoqmd/service-policy/compare/v0.3.0...v0.3.1) (2026-07-14)


### Documentation

* update http examples for app-nested routes and composite identity ([#133](https://github.com/ricardoqmd/service-policy/issues/133)) ([ce308db](https://github.com/ricardoqmd/service-policy/commit/ce308db248fddad57f6bf72a6c7856b29ff39ffd))

## [0.3.0](https://github.com/ricardoqmd/service-policy/compare/v0.2.4...v0.3.0) (2026-07-14)


### ⚠ BREAKING CHANGES

* all v1 routes move under /v1/apps/{app}/ and `app` must not be sent in request bodies (POST /v1/policies → POST /v1/apps/{app}/policies; POST /v1/evaluate → POST /v1/apps/{app}/evaluate; GET /v1/permissions?app= → GET /v1/apps/{app}/permissions). GET /v1/policies remains as a read-only cross-app catalogue.

### Features

* composite policy identity (app, policyId) with app-nested routes ([#130](https://github.com/ricardoqmd/service-policy/issues/130)) ([8fbe47e](https://github.com/ricardoqmd/service-policy/commit/8fbe47ee8af88b810eea8030a129870e31efd35e))

## [0.2.4](https://github.com/ricardoqmd/service-policy/compare/v0.2.3...v0.2.4) (2026-07-14)


### Documentation

* add ADR-026 composite policy identity and app-nested routes ([#127](https://github.com/ricardoqmd/service-policy/issues/127)) ([f1e718a](https://github.com/ricardoqmd/service-policy/commit/f1e718a54979489aca27b3fc58ad1590e7287cb8))

## [0.2.3](https://github.com/ricardoqmd/service-policy/compare/v0.2.2...v0.2.3) (2026-07-13)


### Documentation

* update http examples for application scoping and status filter ([#124](https://github.com/ricardoqmd/service-policy/issues/124)) ([f0d88cc](https://github.com/ricardoqmd/service-policy/commit/f0d88ccf43161c1f9045981544a2189db2f232ac))

## [0.2.2](https://github.com/ricardoqmd/service-policy/compare/v0.2.1...v0.2.2) (2026-07-13)


### Features

* list policies in all lifecycle states with a status filter ([#121](https://github.com/ricardoqmd/service-policy/issues/121)) ([d5accf7](https://github.com/ricardoqmd/service-policy/commit/d5accf76867bfb36772e84070f0c7091d9ed0198))

## [0.2.1](https://github.com/ricardoqmd/service-policy/compare/v0.2.0...v0.2.1) (2026-07-13)


### Documentation

* add ADR-025 policy list status filter ([#118](https://github.com/ricardoqmd/service-policy/issues/118)) ([c8eb3ee](https://github.com/ricardoqmd/service-policy/commit/c8eb3ee8ba701a041d855ec38356ceeaecba4197))

## [0.2.0](https://github.com/ricardoqmd/service-policy/compare/v0.1.29...v0.2.0) (2026-07-13)


### ⚠ BREAKING CHANGES

* EvaluationRequest requires `app`; POST /v1/policies requires `app`.

### Features

* scope policies by application (app dimension) ([#113](https://github.com/ricardoqmd/service-policy/issues/113)) ([0a9a46d](https://github.com/ricardoqmd/service-policy/commit/0a9a46d14d8d5ed9d84431b17cf3c4b73742b2cd))

## [0.1.29](https://github.com/ricardoqmd/service-policy/compare/v0.1.28...v0.1.29) (2026-07-13)


### Documentation

* add ADR-024 application scoping ([#110](https://github.com/ricardoqmd/service-policy/issues/110)) ([ddb6a20](https://github.com/ricardoqmd/service-policy/commit/ddb6a201ba348b4c07daeb0c6bba2b82ec56ba7b))

## [0.1.28](https://github.com/ricardoqmd/service-policy/compare/v0.1.27...v0.1.28) (2026-07-12)


### Documentation

* replace stale policies-demo.http with runnable contract walkthroughs ([#107](https://github.com/ricardoqmd/service-policy/issues/107)) ([bcb1555](https://github.com/ricardoqmd/service-policy/commit/bcb155577e3c05a1ea3b90714c6de39d6d3dc9ac))

## [0.1.27](https://github.com/ricardoqmd/service-policy/compare/v0.1.26...v0.1.27) (2026-07-10)


### Documentation

* document policy administration surface and update roadmap ([#104](https://github.com/ricardoqmd/service-policy/issues/104)) ([57ffc98](https://github.com/ricardoqmd/service-policy/commit/57ffc98636bae0ad1a83b399cb67eaee1b265dba))

## [0.1.26](https://github.com/ricardoqmd/service-policy/compare/v0.1.25...v0.1.26) (2026-07-10)


### Bug Fixes

* reject literal operand mistyping at authoring, deny at evaluation ([#101](https://github.com/ricardoqmd/service-policy/issues/101)) ([f725f00](https://github.com/ricardoqmd/service-policy/commit/f725f00834bdc59699419b1410923c7e0c656bb7))

## [0.1.25](https://github.com/ricardoqmd/service-policy/compare/v0.1.24...v0.1.25) (2026-07-09)


### Documentation

* add ADR-023 operand type validation ([#98](https://github.com/ricardoqmd/service-policy/issues/98)) ([82d5c36](https://github.com/ricardoqmd/service-policy/commit/82d5c36d58d423ebe6db0afe86a9367d4ea5b87b))

## [0.1.24](https://github.com/ricardoqmd/service-policy/compare/v0.1.23...v0.1.24) (2026-07-09)


### Code Refactoring

* centralize error contract in neutral problem package ([#93](https://github.com/ricardoqmd/service-policy/issues/93)) ([5a52375](https://github.com/ricardoqmd/service-policy/commit/5a52375c34a2a7376aa5a054d8d7420a139c22db))

## [0.1.23](https://github.com/ricardoqmd/service-policy/compare/v0.1.22...v0.1.23) (2026-07-09)


### Features

* evaluator cutover to head-pointer model; remove legacy policy store  ([#89](https://github.com/ricardoqmd/service-policy/issues/89)) ([286e58c](https://github.com/ricardoqmd/service-policy/commit/286e58c226638a309dbe5a30aea8d70839f579f0))

## [0.1.22](https://github.com/ricardoqmd/service-policy/compare/v0.1.21...v0.1.22) (2026-07-08)


### Features

* add policy activation endpoints (explicit-version activate, deactivate) ([#86](https://github.com/ricardoqmd/service-policy/issues/86)) ([7bafd93](https://github.com/ricardoqmd/service-policy/commit/7bafd93d344c9899d9282f3d569d458f2f5cf113))

## [0.1.21](https://github.com/ricardoqmd/service-policy/compare/v0.1.20...v0.1.21) (2026-07-08)


### Documentation

* add ADR-021 evaluator cutover to head-pointer model ([#83](https://github.com/ricardoqmd/service-policy/issues/83)) ([3d5e0e2](https://github.com/ricardoqmd/service-policy/commit/3d5e0e280d25a237e05a5909af235e80c5e56106))

## [0.1.20](https://github.com/ricardoqmd/service-policy/compare/v0.1.19...v0.1.20) (2026-07-08)


### Documentation

* add ADR-020 activation write-path ([#80](https://github.com/ricardoqmd/service-policy/issues/80)) ([4351c37](https://github.com/ricardoqmd/service-policy/commit/4351c37f571a22556ae7515c4a936799d3e6fc16))

## [0.1.19](https://github.com/ricardoqmd/service-policy/compare/v0.1.18...v0.1.19) (2026-07-08)


### Features

* add policy write endpoints (create head-first, PUT append) with problem+json ([#77](https://github.com/ricardoqmd/service-policy/issues/77)) ([6565e19](https://github.com/ricardoqmd/service-policy/commit/6565e195379e3430b1bfb5b74736c05e4420a574))

## [0.1.18](https://github.com/ricardoqmd/service-policy/compare/v0.1.17...v0.1.18) (2026-07-08)


### Documentation

* note benign 412/404 race in ADR-019 append disambiguation ([#74](https://github.com/ricardoqmd/service-policy/issues/74)) ([c3d460b](https://github.com/ricardoqmd/service-policy/commit/c3d460badfa8f2733d40b4b89a5a12d5580d1989))

## [0.1.17](https://github.com/ricardoqmd/service-policy/compare/v0.1.16...v0.1.17) (2026-07-07)


### Documentation

* add ADR-019 transaction-free write atomicity ([#71](https://github.com/ricardoqmd/service-policy/issues/71)) ([0cc055c](https://github.com/ricardoqmd/service-policy/commit/0cc055c3f61607d39c1521d693bdc101ee9c734b))

## [0.1.16](https://github.com/ricardoqmd/service-policy/compare/v0.1.15...v0.1.16) (2026-07-07)


### Documentation

* add ADR-018 contract (RFC 9457 + conditional writes) ([#68](https://github.com/ricardoqmd/service-policy/issues/68)) ([c8eaf7c](https://github.com/ricardoqmd/service-policy/commit/c8eaf7c211c14a3e0cb51c64c0d97bd0a7e58a78))

## [0.1.15](https://github.com/ricardoqmd/service-policy/compare/v0.1.14...v0.1.15) (2026-07-07)


### Features

* policy read endpoints over head-pointer model ([#65](https://github.com/ricardoqmd/service-policy/issues/65)) ([c109616](https://github.com/ricardoqmd/service-policy/commit/c10961641bea8c3819111959e4d2db48903243ce))

## [0.1.14](https://github.com/ricardoqmd/service-policy/compare/v0.1.13...v0.1.14) (2026-07-07)


### Documentation

* add ADR-017 REST response contract (envelope, pagination, error shape) ([#62](https://github.com/ricardoqmd/service-policy/issues/62)) ([3b37e64](https://github.com/ricardoqmd/service-policy/commit/3b37e644bd4b37e9722fa71c0d3d78cdbf1a333f))

## [0.1.13](https://github.com/ricardoqmd/service-policy/compare/v0.1.12...v0.1.13) (2026-07-07)


### Documentation

* add ADR-015, ADR-016, and an ADR index ([#57](https://github.com/ricardoqmd/service-policy/issues/57)) ([436d539](https://github.com/ricardoqmd/service-policy/commit/436d5392796cc4bafad7cde79e0566832aaf1995))

## [0.1.12](https://github.com/ricardoqmd/service-policy/compare/v0.1.11...v0.1.12) (2026-07-06)


### Documentation

* add ADR-014 for policy lifecycle and CRUD contract ([#52](https://github.com/ricardoqmd/service-policy/issues/52)) ([2b44012](https://github.com/ricardoqmd/service-policy/commit/2b44012c28addd714a30a1dcccfb1d3d9ce29a55))

## [0.1.11](https://github.com/ricardoqmd/service-policy/compare/v0.1.10...v0.1.11) (2026-07-06)


### Features

* **security:** real OIDC/JWKS validation with mode-based authz markers ([#49](https://github.com/ricardoqmd/service-policy/issues/49)) ([c6f8fb3](https://github.com/ricardoqmd/service-policy/commit/c6f8fb380224816ddaaf669287eb3c3ab37a782d))

## [0.1.10](https://github.com/ricardoqmd/service-policy/compare/v0.1.9...v0.1.10) (2026-07-05)


### Documentation

* add ADR-013 for PDP endpoint authorization ([#46](https://github.com/ricardoqmd/service-policy/issues/46)) ([bc913f6](https://github.com/ricardoqmd/service-policy/commit/bc913f665929beb6a190d9911eff146c40b91e89))

## [0.1.9](https://github.com/ricardoqmd/service-policy/compare/v0.1.8...v0.1.9) (2026-07-02)


### Features

* add POST /v1/policies to author policies over HTTP ([#43](https://github.com/ricardoqmd/service-policy/issues/43)) ([1919af2](https://github.com/ricardoqmd/service-policy/commit/1919af21fe3852cbded345e5f4ded630cad40594))

## [0.1.8](https://github.com/ricardoqmd/service-policy/compare/v0.1.7...v0.1.8) (2026-07-01)


### Bug Fixes

* treat a null comparison operand as no-match ([#40](https://github.com/ricardoqmd/service-policy/issues/40)) ([082eb33](https://github.com/ricardoqmd/service-policy/commit/082eb3340f3654efe8b09b8457744166cbc20299))

## [0.1.7](https://github.com/ricardoqmd/service-policy/compare/v0.1.6...v0.1.7) (2026-06-29)


### Features

* replace stub with persistence-backed policy evaluator ([#36](https://github.com/ricardoqmd/service-policy/issues/36)) ([4b3ecd3](https://github.com/ricardoqmd/service-policy/commit/4b3ecd3f9e43cfa599937578ae110fc115005b08))

## [0.1.6](https://github.com/ricardoqmd/service-policy/compare/v0.1.5...v0.1.6) (2026-06-28)


### Documentation

* clarify ADR-010 subject attributes as flat subjectAttributes field ([#32](https://github.com/ricardoqmd/service-policy/issues/32)) ([754e1c5](https://github.com/ricardoqmd/service-policy/commit/754e1c56db2d6c82ba5ddedb37739c1c5b47253d))

## [0.1.5](https://github.com/ricardoqmd/service-policy/compare/v0.1.4...v0.1.5) (2026-06-28)


### Documentation

* add ADR-010 on subject attribute provenance ([#29](https://github.com/ricardoqmd/service-policy/issues/29)) ([abde413](https://github.com/ricardoqmd/service-policy/commit/abde413b2fbf4c66dafe71d856f2716e10734f0d))

## [0.1.4](https://github.com/ricardoqmd/service-policy/compare/v0.1.3...v0.1.4) (2026-06-28)


### Documentation

* add ADR-009 on test coverage tooling and CDI scope convention ([#26](https://github.com/ricardoqmd/service-policy/issues/26)) ([79067c6](https://github.com/ricardoqmd/service-policy/commit/79067c6b2c374ad9b946474478f8186f4e2a11dc))

## [0.1.3](https://github.com/ricardoqmd/service-policy/compare/v0.1.2...v0.1.3) (2026-06-28)


### Features

* persist policies in MongoDB via Panache ([#23](https://github.com/ricardoqmd/service-policy/issues/23)) ([52dfc38](https://github.com/ricardoqmd/service-policy/commit/52dfc38eac875a01608a9a77bb148e55d345414b))

## [0.1.2](https://github.com/ricardoqmd/service-policy/compare/v0.1.1...v0.1.2) (2026-06-27)


### Features

* add pure policy/condition document mappers ([#20](https://github.com/ricardoqmd/service-policy/issues/20)) ([adab8d8](https://github.com/ricardoqmd/service-policy/commit/adab8d886ac1c7debed7c8392270dc38dc1702f1))

## [0.1.1](https://github.com/ricardoqmd/service-policy/compare/v0.1.0...v0.1.1) (2026-06-27)


### Features

* add policy domain model, condition AST, and deny-overrides engine ([#18](https://github.com/ricardoqmd/service-policy/issues/18)) ([4cd4ad7](https://github.com/ricardoqmd/service-policy/commit/4cd4ad74296cb6676e5cd871eee424135eca7243))


### Documentation

* add ADR-008 (MVP policy domain) ([#17](https://github.com/ricardoqmd/service-policy/issues/17)) ([f638847](https://github.com/ricardoqmd/service-policy/commit/f63884750e70d4ef58159698b3b673eff5b62e58))


### Code Refactoring

* address SonarCloud findings ([#14](https://github.com/ricardoqmd/service-policy/issues/14)) ([4ccce31](https://github.com/ricardoqmd/service-policy/commit/4ccce31be439a7e53dd93cea6a51af661be311ec))
