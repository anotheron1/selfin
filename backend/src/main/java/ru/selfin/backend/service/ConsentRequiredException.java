package ru.selfin.backend.service;

/**
 * Незнакомого человека без согласия на обработку персональных данных сервер не заводит (ANO-196): запрос получает
 * 403 с кодом {@code CONSENT_REQUIRED}, экран уводит на вход — к галочке.
 */
public class ConsentRequiredException extends RuntimeException {

    public static final String CODE = "CONSENT_REQUIRED";

    public ConsentRequiredException() {
        super("Нужно согласие на обработку персональных данных");
    }
}
