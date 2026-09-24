package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.FundAccountLink;

import java.util.Optional;
import java.util.UUID;

public interface FundAccountLinkRepository extends JpaRepository<FundAccountLink, UUID> {

    /**
     * Открытая привязка копилки — та, что действует сейчас. Их не больше одной: это стережёт
     * индекс {@code uq_fund_account_links_open}.
     */
    Optional<FundAccountLink> findByFundIdAndLinkedToIsNull(UUID fundId);
}
