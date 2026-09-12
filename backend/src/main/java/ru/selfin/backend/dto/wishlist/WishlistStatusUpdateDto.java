package ru.selfin.backend.dto.wishlist;

/**
 * Тело PATCH-запроса на смену wishlist-статуса.
 *
 * @param status         OPEN | FIXED | DISMISSED
 * @param deleteArtifact удалить ли созданный конверсией артефакт (план или копилку).
 *        {@code null} или {@code false} — артефакт остаётся, поведение как до ANO-103.
 *        Спека {@code 2026-05-29-wishlist-planning-design.md:66} обещает обе ветки:
 *        «сконвертированный артефакт остаётся (или удаляется по явному выбору)».
 *        Явный выбор — это и есть данное поле.
 */
public record WishlistStatusUpdateDto(String status, Boolean deleteArtifact) {

    /** Удаление запрошено явно. {@code null} трактуется как «нет». */
    public boolean deleteArtifactRequested() {
        return Boolean.TRUE.equals(deleteArtifact);
    }
}
