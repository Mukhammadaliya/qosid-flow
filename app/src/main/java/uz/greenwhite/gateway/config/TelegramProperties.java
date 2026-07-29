package uz.greenwhite.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "gateway.telegram")
public class TelegramProperties {

    private boolean enabled = true;
    private String botToken;
    private Long chatId;
    private Integer messageThreadId;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 5000;

    /**
     * Bir xil (sabab-sinfi, host) juftligidagi DLQ ogohlantirishlari shu oyna ichida
     * birlashtiriladi: birinchisi darhol ketadi, qolganlari sanaladi va oyna yopilganda
     * bitta jamlovchi xabar bo'lib chiqadi.
     *
     * <p>2026-07-27 da bitta CB oynasi 212 ta DLQ yozuvi yaratdi va 212 ta alohida Telegram
     * chaqiruviga aylandi: 14 tasi ketdi, 298 tasi {@code 429 retry after N} bilan qaytdi.
     * Bu qiymat — signalning tezligi (birinchi ogohlantirish darhol) bilan kanalning
     * omon qolishi o'rtasidagi muvozanat.
     *
     * <p>DB'dan boshqarilmaydi (SPA'dagi Telegram sahifasida yo'q) — bu operatsion sozlama,
     * env/yaml orqali beriladi.
     */
    private long coalesceWindowMs = 60_000;
}