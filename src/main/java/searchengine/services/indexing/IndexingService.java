package searchengine.services.indexing;

import searchengine.dto.Response;

public interface IndexingService {
    /**
     * Метод запускает полную индексацию всех сайтов
     * или полную переиндексацию, если они уже проиндексированы.
     * Если в настоящий момент индексация или переиндексация уже запущена,
     * метод возвращает соответствующее сообщение об ошибке.
     */
    Response startIndexing();

    /**
     * Метод останавливает текущий процесс индексации (переиндексации).
     * Если в настоящий момент индексация или переиндексация не происходит,
     * метод возвращает соответствующее сообщение об ошибке.
     */
    Response stopIndexing();

    /**
     * Метод добавляет в индекс или обновляет отдельную страницу
     * адрес которой передан в параметре.
     * Если адрес страницы передан неверно, метод должен вернуть соответствующую ошибку.
     * @param url — адрес страницы, которую нужно переиндексировать.
    */
    Response indexPage(String url);
}