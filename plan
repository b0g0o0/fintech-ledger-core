# Fintech Ledger Core

> 토스(Toss), 카카오페이, 네이버페이, Stripe, Wise, Revolut과 같은 현대 핀테크 서비스를 참고하여 설계한 실전형 금융 원장(Ledger) 시스템

---

# 📌 프로젝트 개요

## 프로젝트 비전

Fintech Ledger Core는 단순한 CRUD 프로젝트가 아니다.

실제 금융 시스템이 돈을 관리하는 방식을 직접 구현하는 것을 목표로 한다.

사용자 계좌 생성부터 입금, 출금, 송금, 거래 내역, 감사 로그(Audit), 동시성 제어까지 포함한 종합 금융 플랫폼이다.

---

## 프로젝트 목표

일반적인 포트폴리오:

- 게시판
- 쇼핑몰
- 채팅 서비스
- SNS

위 프로젝트들은 대부분 CRUD(Create, Read, Update, Delete) 중심이다.

반면 핀테크 시스템은 다음을 해결해야 한다.

- 돈의 정합성(Consistency)
- 트랜잭션(Transaction)
- 동시성(Concurrency)
- 보안(Security)
- 감사(Audit)
- 장애 복구(Fault Tolerance)

Fintech Ledger Core는 이러한 실제 금융 시스템의 핵심 개념을 직접 구현하는 프로젝트이다.

---

# 🎯 최종 목표

사용자가 실제 은행 계좌처럼 사용할 수 있는 디지털 금융 플랫폼 구축

예시

```text
회원가입
 ↓
로그인
 ↓
계좌 생성
 ↓
입금
 ↓
출금
 ↓
송금
 ↓
거래내역 조회
 ↓
감사 로그 기록
 ↓
이벤트 발행
 ↓
알림 발송
```

---

# 🏗 전체 시스템 구조

```text
                Client
                   │
                   ▼
             API Gateway
                   │
 ┌─────────────────┼─────────────────┐
 │                 │                 │
 ▼                 ▼                 ▼
Auth          Account         Transaction
Module         Module            Module
 │                 │                 │
 └─────────────────┼─────────────────┘
                   ▼
              Ledger Core
                   │
                   ▼
               Database
                   │
      ┌────────────┴────────────┐
      ▼                         ▼
 Event System              Audit Log
      │                         │
      ▼                         ▼
Notification           Monitoring
```

---

# 📅 개발 로드맵

---

# Phase 1
# 인증(Authentication) 시스템

## 목표

사용자를 안전하게 인증한다.

---

## 기능

### 회원가입

```http
POST /api/v1/auth/signup
```

---

### 로그인

```http
POST /api/v1/auth/login
```

---

### JWT 인증

- Access Token
- Refresh Token

---

### 토큰 재발급

```http
POST /api/v1/auth/refresh
```

---

### 로그아웃

```http
POST /api/v1/auth/logout
```

---

### 내 정보 조회

```http
GET /api/v1/auth/me
```

---

## 학습 요소

- Spring Security
- JWT
- BCrypt
- Refresh Token Rotation
- 인증/인가 구조

---

## 현재 상태

```text
진행률 90~100%
```

---

# Phase 2
# 계좌(Account) 시스템

## 목표

사용자 계좌 생성 및 관리

---

## 기능

### 계좌 생성

```http
POST /api/v1/accounts
```

---

### 내 계좌 조회

```http
GET /api/v1/accounts
```

---

### 계좌 상세 조회

```http
GET /api/v1/accounts/{accountId}
```

---

### 잔액 조회

```http
GET /api/v1/accounts/{accountId}/balance
```

---

### 계좌 소유권 검증

타인 계좌 접근 시

```http
403 Forbidden
```

반환

---

## 학습 요소

- Domain Driven Design
- 계좌 소유권 검증
- REST API 설계

---

## 현재 상태

```text
진행률 80~90%
```

---

# Phase 3
# Ledger Core (금융 원장)

## 목표

모든 돈의 이동을 기록한다.

---

## 핵심 원칙

돈은 직접 수정하지 않는다.

모든 금액 변화는 Ledger Entry로 기록한다.

---

예시

입금

```text
+10000
```

출금

```text
-5000
```

Ledger

```text
+10000
-5000
```

잔액

```text
5000
```

---

## Ledger Entry 구조

```java
LedgerEntry
```

필드

```text
ledgerId
accountId
transactionId
amount
entryType
createdAt
```

---

## 학습 요소

- 금융 원장 설계
- Event Sourcing 개념
- Immutable Data

---

# Phase 4
# 입금(Deposit)

## 목표

사용자 계좌 입금

---

### API

```http
POST /api/v1/deposits
```

---

### 처리 흐름

```text
요청
 ↓
검증
 ↓
Ledger 기록
 ↓
잔액 업데이트
 ↓
거래내역 생성
```

---

# Phase 5
# 출금(Withdrawal)

## 목표

사용자 계좌 출금

---

### API

```http
POST /api/v1/withdrawals
```

---

### 검증

잔액 부족

```text
Insufficient Balance
```

방지

---

### 처리 흐름

```text
요청
 ↓
잔액 검증
 ↓
Ledger 기록
 ↓
잔액 업데이트
 ↓
거래내역 생성
```

---

# Phase 6
# 송금(Transfer)

## 목표

계좌 간 송금

---

예시

```text
A → B

100,000원
```

---

처리

```text
A -100000
B +100000
```

---

## 핵심 조건

반드시

```text
둘 다 성공
```

또는

```text
둘 다 실패
```

해야 함

---

절대

```text
A는 출금됨
B는 입금 안 됨
```

상황이 발생하면 안 된다.

---

## 학습 요소

- ACID Transaction
- 금융 정합성

---

# Phase 7
# 거래 내역(Transaction History)

## 목표

모든 거래 기록 조회

---

### API

```http
GET /api/v1/transactions
```

---

### 검색 기능

- 날짜 범위
- 금액 범위
- 거래 종류
- 거래 상태

---

### 페이지네이션

```text
Cursor Pagination
```

---

# Phase 8
# Audit 시스템

## 목표

모든 중요 행동 기록

---

### 기록 대상

```text
로그인
로그아웃
비밀번호 변경
계좌 생성
입금
출금
송금
```

---

### 기록 정보

```text
누가
언제
어디서
무엇을
```

---

## 학습 요소

- 보안 감사
- 금융 컴플라이언스

---

# Phase 9
# Idempotency

## 목표

중복 요청 방지

---

문제 상황

사용자가

```text
송금
```

버튼을 두 번 클릭

---

잘못된 결과

```text
100000
+
100000
```

---

올바른 결과

```text
1회만 실행
```

---

## 학습 요소

- 결제 시스템 설계
- 멱등성(Idempotency)

---

# Phase 10
# 동시성 제어(Concurrency)

## 목표

이중 출금 방지

---

예시

잔액

```text
100000
```

---

동시에

```text
출금 100000
출금 100000
```

요청 발생

---

잘못된 결과

```text
-100000
-100000
```

---

올바른 결과

```text
1건 성공
1건 실패
```

---

## 기술

### Optimistic Lock

```java
@Version
```

---

### Pessimistic Lock

```sql
FOR UPDATE
```

---

## 학습 요소

- 금융 시스템 동시성
- 데이터 정합성

---

# Phase 11
# 이벤트 기반 아키텍처(Event Driven Architecture)

## 목표

서비스 분리

---

### Kafka 이벤트

```text
AccountCreated
DepositCreated
TransferCompleted
WithdrawalCompleted
```

---

### Consumer

```text
알림 서비스
분석 서비스
감사 서비스
```

---

## 학습 요소

- Kafka
- Event Driven Design
- 분산 시스템

---

# Phase 12
# 알림(Notification)

## 목표

사용자 알림

---

### 채널

- 이메일
- SMS
- 푸시 알림

---

### 예시

```text
입금 완료
출금 완료
송금 완료
```

---

# Phase 13
# 모니터링(Monitoring)

## 목표

시스템 상태 모니터링

---

### 수집 지표

```text
API 응답속도
TPS
에러율
성공률
```

---

### 사용 도구

- Prometheus
- Grafana

---

# Phase 14
# 관측성(Observability)

## 목표

문제 발생 시 추적 가능

---

### Logging

```text
Structured Logging
```

---

### Tracing

```text
OpenTelemetry
```

---

### Dashboard

```text
Grafana
```

---

# Phase 15
# 인프라 및 배포

## Docker

```text
Dockerfile
Docker Compose
```

---

## CI/CD

### GitHub Actions

```text
Build
 ↓
Test
 ↓
Lint
 ↓
Deploy
```

---

## 배포 환경

- AWS
- GCP
- Ubuntu Server
- 자체 서버

---

# 📂 최종 프로젝트 구조

```text
fintech-ledger-core
│
├── auth
├── account
├── ledger
├── deposit
├── withdrawal
├── transfer
├── transaction
├── audit
├── notification
├── event
├── monitoring
├── common
└── infrastructure
```

---

# 🎓 이 프로젝트로 배우는 것

## 백엔드

- Spring Boot
- Spring Security
- JPA
- Hibernate

---

## 금융 시스템

- Ledger
- Transaction
- Audit
- Idempotency

---

## 고급 시스템

- Kafka
- Event Driven Architecture
- Concurrency
- Distributed Systems

---

## 인프라

- Docker
- CI/CD
- Monitoring
- Observability

---

# 🚀 최종 목표

고등학생 수준의 CRUD 프로젝트를 넘어서

실제 토스, 카카오페이, Stripe 같은 핀테크 회사가 사용하는 핵심 개념들을 구현한

**Production-Grade Fintech Backend System**을 구축한다.
