package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByDeletedFalseOrderBySortOrderAscNameAsc();

    Optional<Account> findByDefaultAccountTrueAndDeletedFalse();
}
