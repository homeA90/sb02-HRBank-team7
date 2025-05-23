package com.sprint.project1.hrbank.service.backup;

import com.sprint.project1.hrbank.dto.backup.BackupPagingRequest;
import com.sprint.project1.hrbank.dto.backup.BackupResponse;
import com.sprint.project1.hrbank.dto.backup.BackupSliceResponse;
import com.sprint.project1.hrbank.entity.backup.Backup;
import com.sprint.project1.hrbank.entity.backup.BackupStatus;
import com.sprint.project1.hrbank.entity.file.File;
import com.sprint.project1.hrbank.exception.backup.BackupFailureException;
import com.sprint.project1.hrbank.exception.backup.BackupInProgressException;
import com.sprint.project1.hrbank.exception.backup.BackupLogStorageException;
import com.sprint.project1.hrbank.exception.backup.BackupNotFoundException;
import com.sprint.project1.hrbank.service.backup.helper.BackupHelper;
import com.sprint.project1.hrbank.mapper.backup.BackupMapper;
import com.sprint.project1.hrbank.repository.backup.BackupRepository;
import com.sprint.project1.hrbank.repository.employee.EmployeeRepository;
import com.sprint.project1.hrbank.util.CursorManager;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackupServiceImpl implements BackupService {

  private final BackupRepository backupRepository;
  private final EmployeeRepository employeeRepository;
  private final BackupMapper backupMapper;
  private final BackupHelper backupHelper;

  /**
   * [직원 백업 프로세스 전체 흐름]
   * 1. 현재 진행 중인 백업이 있는지 확인 (중복 방지)
   * 2. 마지막 완료된 백업 시점 이후로 변경된 직원이 있는지 확인
   * 3. 백업 이력(IN_PROGRESS) 생성 및 저장
   * 4. 변경사항 없으면 SKIPPED 처리 후 종료
   * 5. 변경사항 있으면 직원 전체 조회 후 CSV 생성
   * 6. 생성한 CSV를 파일로 저장하고 메타데이터 저장
   * 7. 백업 이력을 COMPLETED로 마무리하고 연결된 파일 등록
   * 8. 예외 발생 시 로그 바이트 생성 → 파일 저장 → 백업 이력을 FAILED로 마무리
   * 9. 최종적으로 백업 이력 정보를 DTO로 변환해 반환
   */
    @Override
    @Transactional
    public BackupResponse triggerManualBackup(String workerIp) {
      validateInProgressBackup();

      Backup backup = new Backup(workerIp);
      backupRepository.save(backup);

      if (!checkBackupNeed()) {
        log.info("직원 이력이 변경 되지 않았습니다. skip 합니다");
        backup.skip();
        return backupMapper.toResponse(backup);
      }

      try {
        File saveFile = backupHelper.backupEmployees();
        backup.complete(saveFile);
        return backupMapper.toResponse(backup);
      } catch (Exception ex) {
        handleBackupFailure(backup, ex);
        throw new BackupFailureException("백업 중 예외 발생", ex);
      }
    }

  @Override
  @Transactional(readOnly = true)
  public BackupSliceResponse searchBackups(BackupPagingRequest request) {
    List<Backup> backups = backupRepository.search(request);

    List<BackupResponse> contents = backups.stream()
        .map(backupMapper::toResponse)
        .toList();

    String nextCursor = backups.isEmpty()
        ? null
        : CursorManager.encode(backups.get(backups.size() - 1).getStartedAt());

    Long nextIdAfter = backups.isEmpty()
        ? null
        : backups.get(backups.size() - 1).getId();

    Integer size = backups.size();
    boolean hasNext = size.equals(request.size());
    Long totalCount = backupRepository.countByConditions(request);

    return new BackupSliceResponse(contents, nextCursor, nextIdAfter, size, totalCount, hasNext);
  }

  @Override
  @Transactional(readOnly = true)
  public BackupResponse findLatestBackup(BackupStatus status) {
    BackupStatus backupStatus = (status != null) ? status : BackupStatus.COMPLETED;

    Backup backup = backupRepository.findTopByStatusOrderByEndedAtDesc(backupStatus)
        .orElseThrow(() -> new BackupNotFoundException(backupStatus));

    return backupMapper.toResponse(backup);
  }




  private void validateInProgressBackup() {
      backupRepository.findTopByStatus(BackupStatus.IN_PROGRESS).ifPresent(b -> {
        throw new BackupInProgressException();
      });
    }

    private boolean checkBackupNeed() {
      Instant lastBackupTime = backupRepository.findTopByStatusOrderByEndedAtDesc(
              BackupStatus.COMPLETED)
          .map(Backup::getEndedAt)
          .orElse(Instant.EPOCH);
      return employeeRepository.existsByUpdatedAtAfter(lastBackupTime);
    }

    private void handleBackupFailure(Backup backup, Exception ex) {
      try {
        File errorFile = backupHelper.writeBackupErrorLog(ex);
        backup.fail(errorFile);
      } catch (IOException ioEx) {
        throw new BackupLogStorageException("로그 파일 저장 실패", ioEx);
      }
    }
}
