package com.firebirdberlin.radiostreamapi;

import android.util.Log;

import com.firebirdberlin.radiostreamapi.models.PlaylistInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {

    private static String TAG = "NightDream.PlaylistParser";

    private static int READ_TIMEOUT = 10000;
    private static int CONNECT_TIMEOUT = 10000;

    private static final String M3U_EXT_FILE_HEADER = "#EXTM3U";
    private static final String M3U_EXT_INFO_PREFIX = "#EXTINF:";

    private static final Integer[] USUAL_BITRATES = new Integer[] { 64, 96, 128, 192, 256};

    public static boolean isPlaylistUrl(URL url) {

        return (getPlaylistFormat(url.getPath()) != null);
    }

    public static PlaylistInfo parsePlaylistUrl(String playlistUrl) {

        try {

            URL url = new URL(playlistUrl);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            urlConnection.setRequestMethod("GET");
            urlConnection.setReadTimeout(READ_TIMEOUT);
            urlConnection.setConnectTimeout(CONNECT_TIMEOUT);

            urlConnection.setDoOutput(true);

            urlConnection.connect();

            int responseCode = urlConnection.getResponseCode();
            if ( responseCode == 200 ) {
                List<String> lines = getResponseLines(url.openStream());
                if (lines != null && !lines.isEmpty()) {
                    PlaylistInfo.Format format = getPlaylistFormat(playlistUrl);
                    if (format != null) {
                        if (format == PlaylistInfo.Format.M3U) {
                            return parseM3U(lines);
                        } else if (format == PlaylistInfo.Format.PLS) {
                            return parsePLS(lines);
                        } else {
                            return errorneousPlaylist(PlaylistInfo.Error.UNSUPPORTED_FORMAT);
                        }
                    } else {
                        return errorneousPlaylist(PlaylistInfo.Error.UNSUPPORTED_FORMAT);
                    }
                } else {
                    return errorneousPlaylist(PlaylistInfo.Error.INVALID_CONTENT);
                }
            } else {
                Log.e(TAG, "status code " + responseCode);
            }
            urlConnection.disconnect();

        }
        catch (SocketTimeoutException e) {
            Log.e(TAG, "Http Timeout");
        }
        catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }

        return errorneousPlaylist(PlaylistInfo.Error.UNREACHABLE_URL);
    }

    private static PlaylistInfo errorneousPlaylist(PlaylistInfo.Error error) {
        PlaylistInfo p = new PlaylistInfo();
        p.valid = false;
        p.error = error;
        return p;
    }

    private static List<String> getResponseLines(InputStream inputStream) throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            lines.add(line);
        }
        br.close();
        return lines;
    }

    private static PlaylistInfo.Format getPlaylistFormat(String url) {
        String sLower = url.toLowerCase();
        if (sLower.endsWith(".m3u")) {
            return PlaylistInfo.Format.M3U;
        } else if (sLower.endsWith(".pls")) {
            return PlaylistInfo.Format.PLS;
        }
        return null;
    }

    private static PlaylistInfo parseM3U(List<String> lines) {

        if (lines.isEmpty()) {
            return null;
        }

        boolean extendedFormat = lines.get(0).startsWith(M3U_EXT_FILE_HEADER);
        if (extendedFormat) {
            int i = 0;
            for (String line : lines) {
                //find first occurence of a pair of lines, where the first line starts with #EXTINF and the second with a valid stream url
                if (line.startsWith(M3U_EXT_INFO_PREFIX)) {
                    if (i < lines.size() - 1) {
                        String urlString = lines.get(i + 1);
                        if (isValidUrl(urlString)) {
                            PlaylistInfo p = new PlaylistInfo();
                            p.streamUrl = urlString;
                            p.format = PlaylistInfo.Format.M3U;

                            String description = m3UExtInfoDescription(line);
                            if (description != null && !description.isEmpty()) {
                                p.description = description;
                            }

                            p.bitrateHint = bitrateFromUrl(urlString);

                            return p;
                        }
                    }
                }
                i++;
            }
        } else {
            // find first line beginning with http:// or https:// and use this as stream url
            for (String line : lines) {
                if (isValidUrl(line)) {
                    PlaylistInfo p = new PlaylistInfo();
                    p.streamUrl = line;
                    p.format = PlaylistInfo.Format.M3U;
                    return p;
                }
            }
        }

        return null;
    }

    private static boolean isValidUrl(String s) {
        if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                String urlString = s.trim();
                new URL(urlString);
               return true;
            } catch (MalformedURLException e) {

            }
        }
        return false;
    }

    /**
     * find bitrate hints in a url
     */
    private static Integer bitrateFromUrl(String url) {
        for (Integer bitrate : USUAL_BITRATES) {
            if (url.indexOf("/" + String.valueOf(bitrate) + "/") > 0) {
                return bitrate;
            }
        }
        return null;
    }

    private static String m3UExtInfoDescription(String line) {
        if (line.length() > M3U_EXT_INFO_PREFIX.length()) {
            //get part after first comma, skipping runtime, which is usually -1 for streams
            int offsetDescription = line.indexOf(',');
            if (offsetDescription > 0 && line.length() > offsetDescription + 1) {
                String description = line.substring(offsetDescription + 1);
                return  description;
            }
        }
        return null;
    }

    private static PlaylistInfo parsePLS(List<String> lines) {
        return null;
    }
}
