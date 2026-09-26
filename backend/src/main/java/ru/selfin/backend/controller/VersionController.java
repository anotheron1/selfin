package ru.selfin.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Коммит, из которого собран бэк: про стенд и сервер пишется проба, а не память (ANO-194, ворота 4).
 * Коммит приходит аргументом сборки образа GIT_COMMIT; без него — «unknown».
 */
@RestController
@RequestMapping("/api/v1")
public class VersionController {

    private final String commit;

    public VersionController(@Value("${GIT_COMMIT:unknown}") String commit) {
        this.commit = commit;
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("commit", commit);
    }
}
