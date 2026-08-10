package com.example.kladdo.repository;

import com.example.kladdo.model.Tender;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TenderRepository extends JpaRepository<Tender, Long>, JpaSpecificationExecutor<Tender> {

    @Override
    @EntityGraph(attributePaths = {"client"})
    Page<Tender> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"client"})
    Page<Tender> findAll(Specification<Tender> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"client"})
    Optional<Tender> findById(Long id);

    /** Currency codes ordered by how often they're used, for pre-selecting the most common one. */
    @Query("select t.currency from Tender t where t.currency is not null group by t.currency order by count(t) desc")
    List<String> findMostUsedCurrencies(Pageable pageable);

    /**
     * Tenders still in play whose deadline falls in the given window - drives the deadline reminder.
     * Closed and cancelled tenders are excluded: their deadline no longer needs chasing.
     */
    @Query("""
            select t from Tender t
            where t.deadline between :from and :to
              and (t.status is null or t.status not in ('CLOSED', 'CANCELLED'))
            """)
    List<Tender> findUpcomingDeadlines(@org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
                                       @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);
}
