package com.example.demo.repository;

import com.example.demo.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    // korisnici jedne firme (ADMIN-i nisu ovdje - oni nemaju companyId)
    List<User> findByCompanyId(Long companyId);

    /**
     * Brise SVE korisnike jedne firme - pri trajnom praznjenju.
     *
     * Upitom, ne ucitavanjem pa {@code deleteAll(entiteti)}: to drugo je pri praznjenju firme
     * tiho preskakalo retke, bez ijedne greske. Vidi {@code TemplateRepository}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from User e where e.companyId = :companyId")
    int deleteByCompanyIdBulk(@Param("companyId") Long companyId);
}
