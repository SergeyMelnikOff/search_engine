package searchengine.services.indexing;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import searchengine.config.Messages;
import searchengine.config.SiteList;
import searchengine.dto.Response;
import searchengine.dto.indexing.IndexingErrorResponse;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.model.*;
import searchengine.parsing.sitemapping.SiteParser;
import searchengine.parsing.sitemapping.Utils;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import javax.transaction.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SiteParser siteParser;
    private final SiteList siteListFromConfig;
    private final List<Site> siteList = new ArrayList<>();
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private ThreadPoolExecutor executor;

     /* Запуск полной индексации
     * @return response (успешно или ошибка)
     */
    @Override
    public Response startIndexing() {
        Response response;
        SiteParser.setCancel(false);
        if (isIndexingSuccessful()) {
            IndexingResponse responseTrue = new IndexingResponse();
            responseTrue.setResult(true);
            response = responseTrue;
        } else {
            IndexingErrorResponse responseFalse = new IndexingErrorResponse();
            responseFalse.setResult(false);
            responseFalse.setError(Messages.INDEXING_HAS_ALREADY_STARTED);
            response = responseFalse;
        }
        return response;
    }

    /**
     * Индексация по списку из конфигурации
     * @return true -Успешно, false -ошибка
     */
    private boolean isIndexingSuccessful() {
        if (siteListFromConfig.getSites().stream()
                .map(e -> siteRepository.countByNameAndStatus(e.getName(), Status.INDEXING))
                .reduce(0, Integer::sum) > 0) {
            return false;
        }
        siteParser.clearUniqueLinks();

        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("Cайт: %d")
                .build();
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1, threadFactory);
        executor.setMaximumPoolSize(Runtime.getRuntime().availableProcessors());

        siteListFromConfig.getSites().forEach(e -> {
            boolean isCreate = !siteRepository.existsByName(e.getName());
            if (SiteParser.isCancel()) {
                executor.shutdownNow();
            } else {
                executor.execute(() -> parseOneSite(e.getUrl(), e.getName(), isCreate));
            }
        });

        executor.shutdown();
        return true;
    }

    /**
     * Парсинг заданного сайта
     * @param url      ссылка на сайт
     * @param name     имя сайта
     * @param isCreate true -новая запись, false - изменить запись
     */
    @Transactional
    void parseOneSite(String url, String name, boolean isCreate) {
        if (SiteParser.isCancel()) {
            return;
        }
        Site site;
        int siteId;
        if (isCreate) {
            site = new Site(Status.INDEXING, Utils.setNow(), url, name);
            log.info("***** Site '{}' added", name);
        } else {
            site = siteRepository.findByName(name).orElse(null);
            if (site == null) {
                log.warn("***** Сайт {} не найден", name);
                return;
            }
            site.setStatus(Status.INDEXING);

            log.info("****** Site '{}' changed", site.getName());
            deleteByName(name);
        }

        site = siteRepository.save(site);
        siteId = site.getSiteId();
        siteList.add(site);

        /* подготовка данных */
        siteParser.initSiteParser(siteId, Utils.getProtocolAndDomain(url), url);

        /* вызов парсинга сайта */
        siteParser.getLinks();
    }

    /**
     * Удаление страниц и лемм
     * @param name имя сайта
     */
    void deleteByName(String name) {
        Optional<Site> siteByName = siteRepository.findByName(name);
        if (siteByName.isPresent()) {
            int siteId = siteByName.get().getSiteId();

            log.warn("lemma deleteAllBySiteId: {}", siteId);
            try {
                lemmaRepository.deleteAllBySiteId(siteId);
            } catch (Exception e) {
                log.error("lemmaRepository.deleteAllBySiteIdInBatch() message: {}", e.getMessage());
            }
            log.warn("page deleteAllBySiteId: {}", siteId);
            try {
                pageRepository.deleteAllBySiteId(siteId);
            } catch (Exception e) {
                log.error("pageRepository.deleteAllBySiteIdInBatch() message: {}", e.getMessage());
            }
        }
    }

    /******************************************************************************************
     * Метод останавливает текущий процесс индексации
     * @return response (Успешно или ошибка)
     */
    @Override
    public Response stopIndexing() {
        Response response;
        if (isStopIndexing()) {
            IndexingResponse responseTrue = new IndexingResponse();
            responseTrue.setResult(true);
            response = responseTrue;
        } else {
            IndexingErrorResponse responseFalse = new IndexingErrorResponse();
            responseFalse.setResult(false);
            responseFalse.setError(Messages.INDEXING_IS_NOT_RUNNING);
            response = responseFalse;
        }
        return response;
    }

    /**
     * Остановка выполнения индексации
     * @return true -Успешно, false -ошибка
     */
    private boolean isStopIndexing() {
        try {
            long size = siteList.stream().filter(e -> e.getStatus() == Status.INDEXING).count();
            if (size == 0) {
                log.warn(Messages.INDEXING_IS_NOT_RUNNING);
                return false;
            }

            siteParser.forceStop();

            siteList.stream()
                    .filter(e -> e.getStatus() == Status.INDEXING)
                    .forEach(e -> {
                        e.setStatus(Status.FAILED);
                        e.setStatusTime(new Timestamp(System.currentTimeMillis()));
                        e.setLastError(Messages.INDEXING_STOPPED_BY_USER);
                    });
            siteRepository.saveAll(siteList);

            log.warn(Messages.INDEXING_STOPPED_BY_USER);
        } catch (Exception e) {
            log.error(e.getMessage());
            return false;
        }
        return true;
    }

    /******************************************************************************************
     * Добавление или обновление отдельной страницы
     * @param url адрес страницы, которую нужно переиндексировать.
     * @return response (Успешно или ошибка)
     */
    @Override
    public Response indexPage(String url) {
        Response response;
        if (isIndexPage(url)) {
            IndexingResponse responseTrue = new IndexingResponse();
            responseTrue.setResult(true);
            response = responseTrue;
        } else {
            IndexingErrorResponse responseFalse = new IndexingErrorResponse();
            responseFalse.setResult(false);
            responseFalse.setError(
                    Messages.THIS_PAGE_IS_LOCATED_OUTSIDE_THE_SITES_SPECIFIED_IN_THE_CONFIGURATION_FILE);
            response = responseFalse;
        }
        return response;
    }

    /**
     * Индексация отдельной страницы сайта если найдена в БД то изменение (удаление индексов, лемм и
     * страниц), иначе создание записи в таблице siteE
     * @return true -Успешно, false -ошибка
     */
    private boolean isIndexPage(String url) {
        SiteParser.setCancel(false);
        String domain = Utils.getProtocolAndDomain(url);

        if (siteListFromConfig.getSites().stream()
                .noneMatch(site -> site.getUrl().equals(domain))) {
            return false;
        }
        searchengine.config.Site site = siteListFromConfig.getSites().stream()
                .filter(s -> s.getUrl().equals(domain))
                .findFirst()
                .orElse(null);
        if (site == null) {
            return false;
        }

        String name = site.getName();

        Site siteE = siteRepository.findByName(name).orElse(null);
        if (siteE == null) {
            siteE = new Site(Status.INDEXING, Utils.setNow(), domain, name);
        } else {
            siteE.setStatus(Status.INDEXING);
            siteE.setStatusTime(Utils.setNow());

            String path = url.substring(domain.length());
            deletePage(siteE.getSiteId(), path);
        }
        siteE.setLastError("");
        siteRepository.save(siteE);

        if (!saveLemmasAndIndicesForOnePage(url, siteE, domain)) {
            return false;
        }

        siteE.setStatus(Status.INDEXED);
        siteE.setStatusTime(Utils.setNow());
        siteRepository.save(siteE);
        log.info("page saved");
        return true;
    }

    /**
     * Парсинг и сохранение лемм и индексов
     * @param url    - url
     * @param site  - сущность site
     * @param domain - домен
     * @return true если успешно
     */
    private boolean saveLemmasAndIndicesForOnePage(String url, Site site, String domain) {
        Page page = null;
        try {
            page = siteParser.savePage(url, site, domain);
        } catch (Exception e) {
            log.warn("siteParser.savePage - error");
        }

        if (page == null) {
            return false;
        }
        siteParser.parseSinglePage(page);
        return true;
    }

    /**
     * Удаляет страницу
     * @param siteId - id сайта
     * @param path   - путь
     */
    private void deletePage(int siteId, String path) {
        log.info("The page {} by sideId: {} is deleted", path, siteId);
        Page page = pageRepository.findBySiteIdAndPath(siteId, path);
        if (page != null) {
            deleteLemmas(page, siteId);
            pageRepository.delete(page);
        }
    }

    /**
     * Уменьшает Frequency и Удаляет леммы == 0
     * @param page   - страница
     * @param siteId - id сайта
     */
    private void deleteLemmas(Page page, int siteId) {
        List<Index> indexList = indexRepository.findByPageId(page.getPageId());
        List<Lemma> lemmaList = new ArrayList<>();
        indexList.forEach(e -> {
                    Lemma lemma = lemmaRepository.findByLemmaId(e.getLemmaId());
                    lemma.setFrequency(lemma.getFrequency() - 1);          // Frequency - 1
                    lemmaList.add(lemma);
                }
        );
        lemmaRepository.saveAll(lemmaList);
        log.info("Lemmas by pageId: {} are removed", page.getPageId());
        lemmaRepository.deleteBySiteIdAndFrequency(siteId, 0);      // delete if Frequency == 0
    }
}
