# fintech-ledger-core

Production-grade fintech backend — portfolio project.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.3 |
| Security | Spring Security 6 + JWT (JJWT) |
| Persistence | Spring Data JPA + PostgreSQL 16 |
| Cache / Lock | Redis 7 (Redisson) |
| Migration | Flyway |
| API Docs | SpringDoc OpenAPI 3 |
| Build | Gradle 8.8 |
| Infra | Docker + Docker Compose |

## Quick Start

```bash
# 1. Copy environment file
cp .env.example .env
# Edit .env — set JWT_SECRET at minimum

# 2. Start PostgreSQL + Redis
docker compose up postgres redis -d

# 3. Run the application (local profile)
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. Open Swagger UI
open http://localhost:8080/swagger-ui.html
```

## Project Structure

```
src/main/java/com/bugeon/fintechledger/
├── FintechLedgerApplication.java
├── auth/               # Identity & Access bounded context
│   ├── domain/         User, RefreshToken, UserStatus
│   └── repository/     UserRepository, RefreshTokenRepository
├── account/            # Account Management bounded context
│   ├── domain/         Account, AccountStatus
│   └── repository/     AccountRepository
├── transaction/        # Money Movement bounded context
│   ├── domain/         Transaction, TransactionType, TransactionStatus
│   └── repository/     TransactionRepository
├── ledger/             # Double-Entry Ledger (internal — no HTTP)
│   ├── domain/         LedgerEntry, EntryType
│   └── repository/     LedgerEntryRepository
├── outbox/             # Transactional Outbox Pattern
│   ├── domain/         OutboxEvent, EventStatus
│   └── repository/     OutboxEventRepository
├── audit/              # Immutable Audit Trail
│   ├── domain/         AuditLog, AuditAction, AuditResult
│   └── repository/     AuditLogRepository
├── common/             # Shared kernel
│   ├── domain/         BaseEntity
│   ├── exception/      BusinessException, ErrorCode
│   └── web/            ApiResponse, PageResponse
└── infrastructure/
    └── config/         SecurityConfig, RedisConfig, SwaggerConfig
                        JwtProperties, IdempotencyProperties, OutboxProperties
```

## Database Migrations

| Version | Table | Notes |
|---------|-------|-------|
| V1 | users | BCrypt password, status lifecycle |
| V2 | refresh_tokens | Single-use rotation |
| V3 | accounts | Pessimistic lock target, cached_balance |
| V4 | transactions | Idempotency key, status FSM, JSONB payload |
| V5 | ledger_entries | Immutable double-entry records |
| V6 | outbox_events | SKIP LOCKED polling, retry backoff |
| V7 | audit_logs | Append-only, REQUIRES_NEW transaction |

## Key Design Decisions

- **Double-Entry Ledger** — `ledger_entries` is the source of truth. `accounts.cached_balance` is a read projection only, verified by `ReconciliationService`.
- **Pessimistic Locking** — `AccountRepository.findByIdForUpdate()` issues `SELECT FOR UPDATE`. Transfer locks both accounts in ascending UUID order to prevent deadlocks.
- **Idempotency** — `Idempotency-Key` header required on all mutating endpoints. Redis stores `PROCESSING → COMPLETE` state with 24h TTL.
- **Outbox Pattern** — Domain events are written in the same transaction as the money movement. `OutboxProcessor` polls with `FOR UPDATE SKIP LOCKED` for safe multi-instance delivery.
- **Audit** — `AuditService` writes in `Propagation.REQUIRES_NEW` so audit records survive business transaction rollback.

## Development Roadmap

- **Phase 1** — Auth (signup, login, JWT, refresh token)
- **Phase 2** — Account CRUD + balance cache
- **Phase 3** — Deposit / Withdraw / Transfer + Ledger
- **Phase 4** — Idempotency filter + concurrency tests
- **Phase 5** — Audit logging + Outbox scheduler
- **Phase 6** — Reconciliation service
- **Phase 7** — OpenAPI completion + Testcontainers suite
