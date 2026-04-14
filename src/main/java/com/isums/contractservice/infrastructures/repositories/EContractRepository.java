package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EContractRepository extends JpaRepository<EContract, UUID>, JpaSpecificationExecutor<EContract> {

    Optional<EContract> findByDocumentId(String documentId);

    Optional<EContract> findByDocumentNo(String documentNo);

    List<EContract> findAllByOrderByCreatedAtAsc();

    Optional<EContract> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<EContract> findByHouseIdAndUserId(UUID houseId, UUID userId);

    List<EContract> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<EContract> findByStatusAndEndAtBefore(EContractStatus status, Instant endAt);

    List<EContract> findByStatusAndEndAtBetween(EContractStatus status, Instant from, Instant to);

    List<EContract> findByStatusInAndEndAtBefore(List<EContractStatus> statuses, Instant endAt);

    List<EContract> findByStatusInAndEndAtBetween(List<EContractStatus> statuses, Instant from, Instant to);

    @Query("SELECT e.status, COUNT(e) FROM EContract e WHERE e.userId = :userId GROUP BY e.status")
    List<Object[]> countByStatusGrouped(@Param("userId") UUID userId);

    @Query("SELECT e.status, COUNT(e) FROM EContract e GROUP BY e.status")
    List<Object[]> countByStatusGroupedAll();

    @Query(value = """
            SELECT TO_CHAR(DATE_TRUNC('month', created_at), 'YYYY-MM') AS month,
                   COUNT(*) AS count
            FROM econtracts
            WHERE user_id = :userId AND status <> 'DELETED'
              AND created_at >= :fromDate AND created_at < :toDate
            GROUP BY DATE_TRUNC('month', created_at)
            ORDER BY DATE_TRUNC('month', created_at)
            """, nativeQuery = true)
    List<Object[]> countByMonthInRange(@Param("userId") UUID userId,
                                       @Param("fromDate") Instant fromDate,
                                       @Param("toDate") Instant toDate);

    @Query(value = """
            SELECT TO_CHAR(DATE_TRUNC('month', created_at), 'YYYY-MM') AS month,
                   COUNT(*) AS count
            FROM econtracts
            WHERE status <> 'DELETED'
              AND created_at >= :fromDate AND created_at < :toDate
            GROUP BY DATE_TRUNC('month', created_at)
            ORDER BY DATE_TRUNC('month', created_at)
            """, nativeQuery = true)
    List<Object[]> countByMonthInRangeAll(@Param("fromDate") Instant fromDate,
                                          @Param("toDate") Instant toDate);

    @Query("""
            SELECT COUNT(DISTINCT e.houseId) FROM EContract e
            WHERE e.userId = :userId
              AND ((e.status = :completed AND e.endAt BETWEEN :now AND :deadline)
                   OR e.status IN :terminatingStatuses)
            """)
    long countHousesExpiringSoon(@Param("userId") UUID userId,
                                 @Param("completed") EContractStatus completed,
                                 @Param("now") Instant now,
                                 @Param("deadline") Instant deadline,
                                 @Param("terminatingStatuses") List<EContractStatus> terminatingStatuses);

    @Query("""
            SELECT COUNT(DISTINCT e.houseId) FROM EContract e
            WHERE (e.status = :completed AND e.endAt BETWEEN :now AND :deadline)
               OR e.status IN :terminatingStatuses
            """)
    long countHousesExpiringSoonAll(@Param("completed") EContractStatus completed,
                                    @Param("now") Instant now,
                                    @Param("deadline") Instant deadline,
                                    @Param("terminatingStatuses") List<EContractStatus> terminatingStatuses);

    @Query("SELECT COUNT(DISTINCT e.houseId) FROM EContract e")
    long countDistinctHouses();

    @Query("SELECT COUNT(DISTINCT e.houseId) FROM EContract e WHERE e.status = :status")
    long countDistinctHousesByStatus(@Param("status") EContractStatus status);

    @Query("SELECT e.status, COUNT(e) FROM EContract e WHERE e.houseId IN :houseIds GROUP BY e.status")
    List<Object[]> countByStatusGroupedByHouseIds(@Param("houseIds") List<UUID> houseIds);

    @Query(value = """
            SELECT TO_CHAR(DATE_TRUNC('month', created_at), 'YYYY-MM') AS month,
                   COUNT(*) AS count
            FROM econtracts
            WHERE house_id IN :houseIds AND status <> 'DELETED'
              AND created_at >= :fromDate AND created_at < :toDate
            GROUP BY DATE_TRUNC('month', created_at)
            ORDER BY DATE_TRUNC('month', created_at)
            """, nativeQuery = true)
    List<Object[]> countByMonthInRangeByHouseIds(@Param("houseIds") List<UUID> houseIds,
                                                  @Param("fromDate") Instant fromDate,
                                                  @Param("toDate") Instant toDate);

    @Query("""
            SELECT COUNT(DISTINCT e.houseId) FROM EContract e
            WHERE e.houseId IN :houseIds
              AND ((e.status = :completed AND e.endAt BETWEEN :now AND :deadline)
                   OR e.status IN :terminatingStatuses)
            """)
    long countHousesExpiringSoonByHouseIds(@Param("houseIds") List<UUID> houseIds,
                                           @Param("completed") EContractStatus completed,
                                           @Param("now") Instant now,
                                           @Param("deadline") Instant deadline,
                                           @Param("terminatingStatuses") List<EContractStatus> terminatingStatuses);
}
