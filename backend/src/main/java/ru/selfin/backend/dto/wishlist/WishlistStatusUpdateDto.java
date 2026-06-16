package ru.selfin.backend.dto.wishlist;

/**
 * Тело PATCH-запроса на смену wishlist-статуса.
 *
 * @param status OPEN | FIXED | DISMISSED
 */
public record WishlistStatusUpdateDto(String status) {}
