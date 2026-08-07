package com.familytodo.adapter.scheduler;

import com.familytodo.application.SeriesService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Заполнение горизонта повторяющихся дел.
 *
 * <p>Раз в час. Чаще незачем: горизонт шестьдесят дней, и опоздание на час не видно никому. Реже —
 * значило бы, что после суточного простоя ближайший день может оказаться незаполненным.
 *
 * <p>Вся работа в {@link SeriesService}: серию заводят из бота, и логика материализации не может
 * жить в джобе, иначе хендлеру пришлось бы дёргать планировщик.
 */
@Component
public class SeriesMaterializationJob {

    private final SeriesService series;

    public SeriesMaterializationJob(SeriesService series) {
        this.series = series;
    }

    @Scheduled(fixedDelayString = "${series.interval-ms:3600000}")
    public void run() {
        series.materialiseAll();
    }
}
