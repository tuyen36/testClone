package com.example.webtts;

import java.util.List;

/**
 * Giao dien chung cho 3 nguon sub online.
 * type: "movie" hoac "tv". season/episode chi dung khi type = "tv".
 */
public interface SubProvider {
    String name();
    List<SubResult> search(String query, String type, int season, int episode)
            throws Exception;
    byte[] download(SubResult r) throws Exception;
}
