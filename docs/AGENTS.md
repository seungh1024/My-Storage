# Folder Move Concurrency Decision Log

## Scope
- This document records agreed decisions for folder move concurrency control.
- Goal: prevent `name_full_path` length overflow (`> 250`) and avoid partial-update orphan states during async move processing.

## Core Problem
- Validation and execution are split in time (request phase vs async consumer phase).
- A lower subtree move can still be in progress while an upper ancestor move starts later.
- Ancestor move can pass validation on stale state, then fail during DFS/batch update with:
  - `Data too long for column 'name_full_path'`
- Result: partial updates, inconsistent `full_path` cache, orphan-like state.

## Agreed Invariants
- `is_moving=true` is applied only to the move root folder record.
- Moving subtree root metadata (`parent_id`, `id_full_path`, `name_full_path`, `name_path_length`) is updated synchronously before lock release.
- Descendants are updated asynchronously by DFS/batch.
- `name_full_path` length includes separator (`/`) and must be `<= 250`.
- Folder paths are normalized to always end with a separator (`/`) for unambiguous prefix/subtree matching.
- Folder create path rule: `new_path = parent_path + folder_name + '/'`.
- No `FOR UPDATE` usage. Concurrency is controlled by distributed lock (owner/drive scope) and application-level checks.

## Agreed Validation Strategy

### 1) Lock and critical section
- Use owner/drive-scoped distributed lock for move request validation and `is_moving` transition.
- Keep critical section short; do all conflict checks and moving-root metadata writes inside it.

### 2) Conflict checks for move
- Before starting move `S -> T`, check:
  - Cycle prevention: `T` must not be inside subtree of `S` (prefix-based ancestor validation).
  - Ancestor moving check for source/target paths (existing policy).
  - Descendant moving impact check:
    - Query descendants by subtree prefix + `is_moving=true`.
    - Use max reserved value from moving descendants to validate ancestor move safety.

### 3) Prefix query boundary
- For descendant-only search, use subtree boundary-safe prefix matching for children paths (exclude self when intended).
- Avoid ambiguous prefix match that can include sibling-like paths.
- Use `root_id` condition together with prefix conditions.

## Reserved Length Model (Moving Descendants)
- Keep per moving-root reserved max length metadata (stored on the moving root row or dedicated table).
- Semantics: maximum projected `name_full_path` length for that moving subtree after its own move target is applied.
- Ancestor move safety check must include:
  - non-moving subtree max length impact
  - moving descendants reserved max impact
- If ancestor move increases path length (`delta > 0`), apply delta to both max values and require final max `<= 250`.
- If `delta <= 0`, length risk is reduced, but cycle/conflict checks are still required.

## Move State Storage Decision
- Adopt a dedicated state table as the primary source for in-flight operations (instead of coupling all state to `folder_metadata.is_moving`).
- Keep `is_moving` temporarily only for compatibility/migration visibility, then remove after full rollout.
- Recommended key options:
  - `PRIMARY KEY (root_id, folder_id)` to isolate operation state by storage root.
  - or `PRIMARY KEY (folder_id)` with secondary indexes including `root_id` if folder id is globally unique and that pattern performs better.
- Recommended columns:
  - `root_id`, `folder_id`
  - `op_type` (`MOVE`, `DELETE`), `op_state` (`ACTIVE`, `DONE`, `FAILED`)
  - `root_id_full_path` (normalized, trailing `/`)
  - `projected_max_name_path_len`
  - `job_id`, `created_at`, `updated_at`
- Query policy:
  - Prefer querying this state table directly in hot validation paths.
  - Avoid unnecessary joins with `folder_metadata` for moving-conflict checks.
- Insert/atomicity policy:
  - Use transaction-scoped insert (`INSERT`) with distributed lock held.
  - Release distributed lock only after transaction commit.

## Write Freeze During Moving
- During `is_moving=true` on subtree root:
  - Block subtree `move`
  - Block subtree `rename`
  - Allow subtree `create` only with guarded flow:
    - Acquire same owner/drive distributed lock
    - Validate direct path length (`parent_path + name + '/' <= 250`)
    - Validate moving-ancestor reserved max impact (projected max with delta must remain `<= 250`)
    - Atomically update moving-ancestor reserved max metadata when needed
    - Persist create path using moving-adjusted parent path (or guarantee async move final sweep catches post-start creates)
- Reason: prevent move/rename lost-update risk and keep reserved max invariant valid while allowing controlled create.

## Async Consumer Policy
- Consumer concurrency currently set to 1 by design (DB resource constraints).
- Still perform defensive checks at consume start because request-time validation and execution-time state can differ.
- Treat deterministic path overflow as non-retryable business failure:
  - Do not endlessly retry on `Data too long`
  - Mark job failed with explicit reason
  - Clear `is_moving` and finalize job state safely

## Failure/Recovery
- Existing possible state: `parent_id` chain is correct, while cached full paths can be inconsistent.
- Recovery strategy:
  - Add/prepare subtree full-path rebuild flow from structural truth (`parent_id` + name).
  - Use for stuck/failed jobs and orphan-like cache inconsistency.

## Lock Wait and Retry Policy
- Current 3s lock wait causes throughput drop under load.
- Prefer short try-lock (fast fail) and client-driven retry with backoff/jitter.
- Return conflict-style response (e.g., retry-later semantics) instead of long server-side waiting.

## Why This Is Needed
- Main issue is move-vs-move interleaving (`A -> B` while ancestor `C -> D` starts from stale view), not create-only behavior.
- Reserved moving-descendant max + guarded create + short critical section addresses correctness and performance together.

## Implementation Notes For Next Step
- Add schema/field for moving reserved max length metadata.
- Update move validation query set to include moving descendants reserved max.
- Add freeze guards for `move/rename` under moving ancestors.
- Add guarded `create` flow under moving ancestors (lock + dual length validation + reserved max update).
- Add dedicated package/domain for move state management (entity/repository/service separated from folder metadata domain).
- Adjust lock wait strategy and API error contract for fast-fail + retry-later.
- Add integration tests for:
  - descendant move in progress, then ancestor move request
  - cycle prevention under concurrent requests
  - deterministic overflow classified as non-retryable
  - recovery flow for partial async failures
## Progress Update (2026-02-16)

### Completed
- Applied `folder_operation_state`-based collision control to both `moveFolder` and `createFolder`.
- Included `root_id` in `folder_job` handling and aligned completion cleanup with `(root_id, folder_id)`.
- Removed `is_moving`-based collision decisions and unified conflict checks on `folder_operation_state`.
- Switched parent-size propagation (folder/file move and file create paths) from async event fan-out to in-transaction batch updates.
- Added `FolderSizeAdjustmentService` and `FolderMetadataRepository.batchUpdateSizeDeltas` to unify size-delta calculation and persistence.
- Strengthened move concurrency integration tests with randomized moves plus integrity checks (prefix/orphan detection) and root-size invariants.

### Validation Alignment
- Updated unit-test expectations for the new move collision policy (block both ancestor and descendant move conflicts).
- Re-aligned failure tests for path-length boundary behavior (`250` allowed) and the updated validation order.

### Remaining Work
- Tune randomized concurrent move integration tests (retry count/task count) to balance stability and CI runtime.
- Finalize regression coverage for size-propagation consistency across move/upload/file-move paths.
- Clean up docs/comments that still reference old `is_moving` assumptions.

### Additional Changes (2026-02-16, Path-Based Policy Hardening)
- Simplified `move vs move` from conditional descendant allowance to strict ancestor/descendant blocking:
  - Ancestor conflict: block when any operation state exists on `source/target` ancestor paths.
  - Descendant conflict: block when any ACTIVE move exists in the `source` subtree.
- Reordered `moveFolder` flow so ancestor conflict validation runs before `getFolderJobLock`.
- Unified create path validation/calculation into `validateAndResolveForCreate`:
  - Enforced single moving-ancestor lookup (`single row or error`).
  - Calculated projected parent path by trimming/rebuilding suffix from `id_full_path` token index of moving root.
  - Standardized path-length boundary check to `> maxPathLength` (`250` is valid).
- On successful create under a moving ancestor, updated projected max length to prevent downstream DFS path overflow.
- Updated related failing tests to match the new validation order and conflict policy.

## Latest Sync Update (2026-02-17)

### What is completed
- Finalized the path-based concurrency policy:
  - `move`: block both ancestor and descendant conflicts (one in-flight move per subtree).
  - `create`: validate against reconstructed projected parent path under moving ancestor.
- Flattened `CreatePathValidationResult` and applied single-row projected-max update on successful create.
- Reordered `moveFolder` so ancestor conflict validation runs before `getFolderJobLock`.
- Updated unit/integration tests to match the new policy and execution order.
- Updated documentation while preserving prior decision history:
  - Added latest root-cause and decision notes to `docs/POST_PATH_BASED_ADDITIONAL_CHANGES_2026-02-16.md`.

### Current status
- Code and docs are aligned with the finalized policy.
- Test rerun/confirmation is still required in an environment that can download Gradle distribution.

### Resume from here
- Core files:
  - `src/main/java/com/woowacamp/storage/domain/folder/service/FolderService.java`
  - `src/main/java/com/woowacamp/storage/global/util/ValidateParentsUtil.java`
  - `src/main/java/com/woowacamp/storage/domain/folderoperation/service/FolderOperationStateService.java`
- Verification files:
  - `src/test/java/com/woowacamp/storage/domain/folder/service/FolderServiceTest.java`
  - `src/test/java/com/woowacamp/storage/global/util/ValidateParentsUtilTest.java`
- Documentation file:
  - `docs/POST_PATH_BASED_ADDITIONAL_CHANGES_2026-02-16.md`
---

# 폴더 이동 동시성 의사결정 문서 (한글)

## 범위
- 이 문서는 폴더 이동 동시성 제어 관련 합의 사항을 기록한다.
- 목표: 비동기 이동 처리 중 `name_full_path` 길이 초과(`> 250`)와 부분 업데이트로 인한 고아/불일치 상태를 방지한다.

## 핵심 문제
- 검증 시점(요청)과 실행 시점(비동기 컨슈머)이 분리되어 있다.
- 하위 서브트리 이동이 진행 중일 때, 상위 조상 이동이 뒤늦게 시작될 수 있다.
- 상위 이동이 오래된 상태로 검증을 통과한 뒤 DFS/배치 업데이트 단계에서 아래 오류가 발생할 수 있다.
  - `Data too long for column 'name_full_path'`
- 결과적으로 부분 반영, `full_path` 캐시 불일치, 고아 유사 상태가 발생한다.

## 합의된 불변식
- `is_moving=true`는 이동 루트 폴더 레코드에만 적용한다.
- 이동 루트 메타데이터(`parent_id`, `id_full_path`, `name_full_path`, `name_path_length`)는 락 해제 전에 동기적으로 갱신한다.
- 하위 노드 갱신은 DFS/배치로 비동기 처리한다.
- `name_full_path` 길이는 구분자(`/`) 포함 기준이며 `<= 250`이어야 한다.
- 폴더 경로는 prefix/서브트리 판별의 정확성을 위해 항상 구분자(`/`)로 끝나도록 정규화한다.
- 폴더 생성 경로 규칙은 `new_path = parent_path + folder_name + '/'`를 따른다.
- `FOR UPDATE`는 사용하지 않는다. 동시성 제어는 드라이브(소유자) 단위 분산락 + 애플리케이션 검증으로 처리한다.

## 합의된 검증 전략

### 1) 락과 임계구역
- 드라이브(소유자) 단위 분산락으로 이동 요청 검증과 `is_moving` 전환을 보호한다.
- 임계구역은 짧게 유지하고, 충돌 검증과 이동 루트 메타 쓰기를 임계구역 내부에서 수행한다.

### 2) 이동 충돌 검증
- `S -> T` 이동 시작 전 아래를 확인한다.
  - 사이클 방지: `T`가 `S`의 서브트리 내부이면 금지 (prefix 기반 조상 검증).
  - source/target 경로의 조상 moving 검증(기존 정책).
  - 하위 moving 영향 검증:
    - 서브트리 prefix + `is_moving=true` 조건으로 하위 moving 루트를 조회한다.
    - 하위 moving 루트의 예약 최대 길이(max reserved value)를 이용해 상위 이동 안전성을 검증한다.

### 3) prefix 조회 경계
- 하위만 조회할 때는 경계가 보장되는 prefix 조건을 사용한다(필요 시 self 제외).
- 형제 경로 오탐이 발생하는 모호한 prefix 매칭은 피한다.
- `root_id` 조건을 prefix 조건과 함께 사용한다.

## 예약 길이 모델 (하위 moving)
- moving 루트마다 예약 최대 길이 메타데이터를 유지한다(루트 행 또는 별도 테이블).
- 의미: 해당 moving 서브트리가 자기 이동 목표까지 반영된 이후 가질 `name_full_path` 최대 길이.
- 상위 이동 검증 시 아래 둘 다 반영한다.
  - non-moving 서브트리 최대 길이 영향
  - moving 하위 루트 예약 최대 길이 영향
- 상위 이동으로 경로가 길어지는 경우(`delta > 0`), 두 최대값 모두 `+ delta` 반영 후 최종 최대가 `<= 250`이어야 한다.
- `delta <= 0`이면 길이 리스크는 줄지만, 사이클/충돌 검증은 계속 필요하다.

## 이동 상태 저장소 의사결정
- 진행 중 작업 상태는 `folder_metadata.is_moving` 단일 컬럼보다 별도 상태 테이블을 정본으로 사용한다.
- `is_moving`은 마이그레이션/호환 관찰을 위해 잠시 유지하고, 전환 완료 후 제거한다.
- 권장 키 구성:
  - 스토리지 루트 단위 분리를 위해 `PRIMARY KEY (root_id, folder_id)`
  - `folder_id` 전역 유니크 기반이 더 유리하면 `PRIMARY KEY (folder_id)` + `root_id` 보조 인덱스
- 권장 컬럼:
  - `root_id`, `folder_id`
  - `op_type` (`MOVE`, `DELETE`), `op_state` (`ACTIVE`, `DONE`, `FAILED`)
  - `root_id_full_path` (정규화, trailing `/`)
  - `projected_max_name_path_len`
  - `job_id`, `created_at`, `updated_at`
- 조회 정책:
  - 핫패스 검증에서는 상태 테이블 직접 조회를 우선한다.
  - moving 충돌 검증에서 `folder_metadata` 조인은 가능한 피한다.
- insert/원자성 정책:
  - 분산락을 잡은 상태에서 트랜잭션 단위 insert(`INSERT`)를 사용한다.
  - 트랜잭션 커밋 이후에만 분산락을 해제한다.

## moving 중 쓰기 동결(Freeze)
- `is_moving=true`인 서브트리에서는 아래 작업을 차단한다.
  - `move`
  - `rename`
  - `create`는 조건부 허용:
    - 동일 드라이브 분산락 획득 후 수행
    - 직접 경로 길이 검증(`parent_path + name + '/' <= 250`)
    - moving 조상 예약 최대 길이 영향 검증(변동 반영 후 `<= 250`)
    - 필요 시 moving 조상의 예약 최대 길이 메타데이터를 원자적으로 갱신
    - 생성 경로 저장은 moving 반영 경로 기준으로 수행(또는 move 비동기 최종 스윕으로 시작 이후 생성분까지 반영 보장)
- 이유: move/rename의 lost-update 위험은 차단하고, create는 예약 길이 불변식이 깨지지 않는 범위에서만 허용하기 위함이다.

## 비동기 컨슈머 정책
- 컨슈머 동시성은 현재 1로 유지한다(DB 자원 제약).
- 요청 시점과 실행 시점 상태가 다를 수 있으므로 컨슘 시작 시 방어 검증을 추가한다.
- 결정적 길이 초과는 비재시도(non-retryable)로 처리한다.
  - `Data too long` 무한 재시도 금지
  - 명시적 실패 사유로 잡 종료
  - `is_moving` 정리 및 잡 상태 안전 종료

## 실패/복구
- 현재 발생 가능한 상태: `parent_id` 체인은 정합하나, `full_path` 캐시가 불일치할 수 있음.
- 복구 전략:
  - 구조 정본(`parent_id` + name) 기반으로 서브트리 `full_path` 재구성(rebuild) 경로를 준비한다.
  - 실패/중단 잡 및 고아 유사 캐시 불일치 복구에 사용한다.

## 락 대기 및 재시도 정책
- 현재 3초 락 대기는 부하 상황에서 처리량 저하를 유발한다.
- 짧은 try-lock 후 빠른 실패(fast-fail), 클라이언트 백오프/지터 재시도를 권장한다.
- 서버에서 오래 대기하기보다 retry-later 성격의 충돌 응답을 반환한다.

## 왜 필요한가
- 핵심 이슈는 create 단독이 아니라 move-vs-move 교차(`A -> B` 진행 중 `C -> D` 시작)다.
- 하위 moving 예약 최대 길이 + create 조건부 허용 + 짧은 임계구역 조합이 정합성과 성능을 함께 맞춘다.

## 다음 구현 단계 메모
- moving 예약 최대 길이 메타데이터를 위한 스키마/필드 추가
- 하위 moving 예약 최대 길이를 포함하는 이동 검증 쿼리 반영
- moving 조상 하위의 `move/rename` 차단 가드 추가
- moving 조상 하위의 `create` 조건부 허용 플로우 추가(락 + 이중 길이 검증 + 예약 최대 길이 갱신)
- 이동 상태 전용 도메인/패키지 분리(`entity/repository/service`) 적용
- 락 대기 전략과 API fast-fail/retry-later 계약 조정
- 통합 테스트 추가:
  - 하위 이동 진행 중 상위 이동 요청
  - 동시 요청 하 사이클 방지
  - 결정적 길이 초과의 non-retryable 분류
  - 비동기 부분 실패 복구 흐름

## 진행 현황 (2026-02-11)

### 완료된 작업
- `folderoperation` 도메인 패키지 생성 완료:
  - `FolderOperationState` / `FolderOperationStateId` / `FolderOperationType` / `FolderOperationStatus`
  - `FolderOperationStateJpaRepository`
  - `FolderOperationStateService`
- 상태 저장 키를 `PRIMARY KEY (root_id, folder_id)` 기준으로 사용하는 구조로 코드 반영.
- `upsert` 제거, `INSERT` 기반으로 전환.
- 중복 키 예외(`DuplicateKeyException`)를 `FOLDER_JOB_CONFLICT`로 변환하는 처리 반영.
- `moveFolder` 흐름 단순화:
  - 트랜잭션 내 롤백을 전제로 별도 `cleanupMoveReservation` 보상 로직 제거.
- `MovePlan` 도입 및 `buildMovePlanAndValidate` 적용.
- 이동 길이 검증 강화:
  - 기존 트리 최대 길이 + 부모 경로 변화량 계산 유지
  - 하위 `ACTIVE MOVE`의 예약 최대 길이(`FolderOperationState`)를 함께 반영
  - 최종 최대값 기준으로 `<= 250` 검증
- `Len` 약어를 `Length`로 통일(필드/파라미터/메서드/인덱스명).
- `ORDER BY ... LIMIT 1` 기반 조회를 `MAX(...)` 조회로 전환:
  - `FolderOperationState` 최대 예약 길이 조회
  - `folder_metadata`/`file_metadata` 최대 경로 길이 조회
- soft-delete 정책 재반영:
  - 최대 길이 계산 시 `is_deleted = false` 조건 포함
  - 파일은 `upload_status != 'FAIL'` 조건 유지
- 인덱스 추가/정리(코드 기준):
  - `idx_folder_operation_state_filter (root_id, operation_state, operation_type)`
  - `idx_folder_operation_state_filter_prefix_max (root_id, operation_state, operation_type, root_id_full_path, projected_max_name_path_length)`
  - `folder_idx_root_deleted_namepath_length (root_id, is_deleted, name_full_path, name_path_length)`
  - `file_idx_root_deleted_namepath_length_status (root_id, is_deleted, name_full_path, name_path_length, upload_status)`

### 주요 이슈와 해결 내용
- 이슈 1: 상위/하위 move 교차 시 검증-실행 시점 차이로 `name_full_path` 초과가 런타임에 발생.
  - 해결: 하위 `ACTIVE MOVE` 예약 최대 길이를 상위 이동 검증에 포함.
- 이슈 2: `ORDER BY ... DESC LIMIT 1`에서 `Using filesort` 발생.
  - 해결: 최대값 필요 쿼리를 `MAX(...)`로 변경.
- 이슈 3: 네이밍 가독성 저하(`Len` 약어).
  - 해결: `Length`로 통일.
- 이슈 4: soft-delete 정책 혼선.
  - 해결: 사용자 경험 기준으로 이동 가능 판단에는 `is_deleted = false` 적용.

### 남은 작업 (우선순위 순)
1. DB 스키마 동기화 확인:
 - `projected_max_name_path_length` 컬럼 실존 여부 확인
 - 기존 구 인덱스(`...len...`) 또는 불필요 인덱스 정리(drop)
2. `EXPLAIN ANALYZE` 재검증:
 - `MAX(...)` 전환 후 `rows examined`, 인덱스 선택, `Using where` 비용 확인
3. 이동 완료/실패 시 상태 정리:
 - `folder_operation_state` 레코드 삭제 시점(성공/실패) 확정 및 구현
4. create/rename 정책 확정 후 반영:
 - moving 조상 존재 시 허용/차단 및 길이 검증/예약 길이 갱신 규칙 코드화
5. 컨슈머 실패 정책 보강:
 - `Data too long`를 non-retryable로 분류
 - 실패 상태 기록 + 복구(경로 rebuild) 플로우 연결
6. 통합 테스트 작성:
 - 하위 move 진행 중 상위 move
 - 동시 요청 사이클 방지
 - 길이 초과 non-retryable 처리
 - 실패 후 복구 검증

### 다음 재개 지점
- 시작 파일:
  - `src/main/java/com/woowacamp/storage/domain/folder/service/FolderService.java`
  - `src/main/java/com/woowacamp/storage/domain/folderoperation/service/FolderOperationStateService.java`
  - `src/main/java/com/woowacamp/storage/domain/folderoperation/repository/FolderOperationStateJpaRepository.java`
- 우선 실행:
  - DB 컬럼/인덱스 실제 상태 점검 후(`SHOW CREATE TABLE`, `EXPLAIN ANALYZE`) 코드-스키마 불일치부터 해소.

## 진행 현황 (2026-02-16)

### 완료
- `folder_operation_state` 기반 이동 충돌 제어를 `moveFolder`/`createFolder` 흐름에 반영했다.
- `createFolder`에서 moving 조상 예약 길이 갱신을 단건 update 반복이 아닌 배치 update로 정리했다.
- `folder_job`에 `root_id`를 포함한 복합키 기준으로 정리하고, 컨슈머 완료 시 `(root_id, folder_id)` 기준 정리를 반영했다.
- `is_moving` 의존 로직을 제거하고, 충돌 판단 기준을 `folder_operation_state`로 일원화했다.
- 폴더/파일 이동 및 파일 생성 시 상위 폴더 용량 반영을 이벤트 비동기 전파 대신 트랜잭션 내 배치 반영으로 전환했다.
- `FolderSizeAdjustmentService`와 `FolderMetadataRepository.batchUpdateSizeDeltas`를 추가해 용량 델타 계산/반영 경로를 통합했다.
- 폴더 이동 동시성 통합 테스트를 강화해 랜덤 이동 + 트리 정합성(prefix/고아 여부) + 루트 용량 불변 검증을 한 테스트로 통합했다.

### 검증 반영
- `moveFolder` 충돌 정책 변경(상/하위 차단)에 맞춰 단위 테스트 기대를 수정했다.
- 경로 길이 경계(`250` 허용)와 검증 순서 변경에 맞춘 실패 케이스를 재정렬했다.

### 현재 남은 작업
- 랜덤 동시 이동 통합 테스트의 실행 안정성(재시도 횟수/태스크 수) 튜닝 및 CI 실행시간 균형점 확정.
- 이동/업로드/파일 이동 경로의 용량 반영 일관성에 대한 최종 회귀 테스트 정리.
- 문서/주석에서 `is_moving` 전제를 참조하는 문구 정리 및 제거.

### 추가 반영 (2026-02-16, path-based 보강)
- `move vs move` 정책을 하향 이동 허용(길이 비교)에서 상/하위 전면 차단으로 단순화했다.
  - 상위 충돌: `source/target` 조상 경로의 operation state 존재 시 차단.
  - 하위 충돌: `source` 서브트리 내부에 ACTIVE move가 있으면 차단.
- `moveFolder` 검증 순서를 정리해 상위 충돌 검증을 `getFolderJobLock` 이전에 수행하도록 변경했다.
- `createFolder` 경로 검증/계산을 `validateAndResolveForCreate` 단일 메서드로 통합했다.
  - moving 조상 조회는 "단건 조회 + 다건이면 예외" 정책으로 고정했다.
  - projected parent path 계산은 `id_full_path` 기준 루트 ID 인덱스 절단 방식으로 정리했다.
  - `name_full_path` 길이 비교는 `> maxPathLength` 기준으로 통일했다(250 허용).
- create 성공 시 moving 조상의 projected max 값을 갱신해, 이동 중 생성이 이후 DFS 업데이트에서 길이 초과를 유발하지 않도록 보강했다.
- `moveFolder` 검증 순서 변경에 따라 관련 실패 테스트 케이스를 정책 기준으로 갱신했다.

## 최신 동기화 (2026-02-17)

### 어디까지 완료했는지
- 경로 기반 동시성 정책을 최종 정리했다:
  - `move`: 상/하위 충돌 모두 차단(서브트리 내 동시 move 1건).
  - `create`: moving 조상 기준 예상 parent 경로를 재구성해 길이 검증.
- `CreatePathValidationResult`를 평탄화하고, create 성공 시 projected max 단건 갱신 흐름으로 반영했다.
- `moveFolder`에서 상위 충돌 검증이 `getFolderJobLock`보다 먼저 실행되도록 순서를 조정했다.
- 관련 단위/통합 테스트를 현재 정책 순서에 맞게 보정했다.
- 진행 내용 문서화:
  - `docs/POST_PATH_BASED_ADDITIONAL_CHANGES_2026-02-16.md`에 기존 기록을 유지한 채, 최신 원인 분석/의사결정 섹션 추가.

### 현재 상태
- 코드/문서는 정책 기준으로 동기화되어 있다.
- 테스트 실행은 환경 제약(Gradle 배포 다운로드 네트워크 제한)으로 이 문맥에서 최종 재실행 확인이 필요하다.

### 다음 재개 지점
- 핵심 파일:
  - `src/main/java/com/woowacamp/storage/domain/folder/service/FolderService.java`
  - `src/main/java/com/woowacamp/storage/global/util/ValidateParentsUtil.java`
  - `src/main/java/com/woowacamp/storage/domain/folderoperation/service/FolderOperationStateService.java`
- 검증 파일:
  - `src/test/java/com/woowacamp/storage/domain/folder/service/FolderServiceTest.java`
  - `src/test/java/com/woowacamp/storage/global/util/ValidateParentsUtilTest.java`
- 문서 파일:
  - `docs/POST_PATH_BASED_ADDITIONAL_CHANGES_2026-02-16.md`
