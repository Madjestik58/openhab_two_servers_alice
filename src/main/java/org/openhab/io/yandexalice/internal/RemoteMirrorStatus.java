package org.openhab.io.yandexalice.internal;

/** Human-readable setup diagnostics. No credentials or server addresses are included. */
final class RemoteMirrorStatus {
    private RemoteMirrorStatus() {
    }

    static String noAddress() {
        return "ℹ Адрес не задан: мост выключен. Для сервера без Алисы это правильно; на сервере с Алисой укажите адрес второго сервера.";
    }

    static String checking() {
        return "⌛ Проверяем доступ к Items и потоку событий второго сервера...";
    }

    static String connected(int count) {
        return count == 0 ? "⚠ Связь есть, но Items с выбранным тегом не найдены. Проверьте теги на втором сервере."
                : "✓ Связь установлена, зеркальных Items: " + count + ".";
    }

    static String failure(Throwable error) {
        String message = String.valueOf(error.getMessage());
        if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
            return "⚠ REST второго сервера отказал в доступе. Укажите его API-токен или разрешите доступ к Items и событиям.";
        }
        if (error instanceof IllegalArgumentException) {
            return "⚠ Проверьте адрес, тег и префикс. Адрес должен быть вида http://server:8080 без пути /rest.";
        }
        if (message.contains("response is empty")) {
            return "⚠ Второй сервер не вернул Items. Проверьте его конфигурацию и доступность REST API.";
        }
        return "⚠ Нет связи с Items второго сервера. Проверьте адрес и сеть; подробности в openhab.log.";
    }

    static String callbacks(String skillId, String oauth) {
        boolean hasId = !skillId.isBlank();
        boolean hasToken = !oauth.isBlank();
        if (hasId && hasToken) {
            return "✓ Обратные уведомления Яндексу настроены.";
        }
        if (hasId || hasToken) {
            return "⚠ Для обратных уведомлений заполните и ID навыка, и OAuth-токен либо оставьте оба поля пустыми.";
        }
        return "ℹ Обратные уведомления Яндексу выключены. Для голосовых команд эти поля не обязательны.";
    }
}
