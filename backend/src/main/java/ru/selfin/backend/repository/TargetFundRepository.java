package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.selfin.backend.model.TargetFund;

import java.util.List;
import java.util.UUID;

public interface TargetFundRepository extends JpaRepository<TargetFund, UUID> {
    List<TargetFund> findAllByDeletedFalseOrderByPriorityAsc();

    // --- Wishlist ---

    List<TargetFund> findByWishlistStatusAndDeletedFalse(
            ru.selfin.backend.model.enums.WishlistStatus status);

    /** Все копилки/кредиты с явным wishlist-статусом. Для страницы /wishlist. */
    @Query("SELECT f FROM TargetFund f WHERE f.wishlistStatus IS NOT NULL AND f.deleted = false")
    List<TargetFund> findAllWishlistFunds();
}
