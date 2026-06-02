package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.UserSettings;

import java.util.Optional;
import java.util.UUID;

public interface UserSettingsRepository extends JpaRepository<UserSettings, UUID> {
    Optional<UserSettings> findBySettingsKey(String settingsKey);
}
