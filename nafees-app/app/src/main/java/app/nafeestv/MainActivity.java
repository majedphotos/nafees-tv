package app.nafeestv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opens this card's video (or playlist) in the YouTube TV app, then gets out of the way. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (BuildConfig.REMINDERS) {
            Reminder.schedule(this);
            Adhan.schedule(this);
            MessageServer.start(this);
        }
        if (!BuildConfig.LATEST) {
            open(BuildConfig.VIDEO);
            return;
        }
        // A series card (Alifba'iyat Al-Nufais): open the newest episode, or the first one when offline.
        new Thread(() -> {
            String id = newest();
            runOnUiThread(() -> open(id != null ? id : BuildConfig.VIDEO));
        }).start();
    }

    private void open(String videoId) {
        String url = "https://www.youtube.com/watch?v=" + videoId;
        if (!BuildConfig.LIST.isEmpty()) url += "&list=" + BuildConfig.LIST; // the whole playlist plays on
        Uri video = Uri.parse(url);
        Intent yt = new Intent(Intent.ACTION_VIEW, video).setPackage("com.google.android.youtube.tv");
        try {
            startActivity(yt);
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, video));
        }
        finish();
    }

    /** The most recently published video in the card's playlist, from YouTube's public RSS feed, or null. */
    private static String newest() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://www.youtube.com/feeds/videos.xml?playlist_id=" + BuildConfig.LIST).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            String xml;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                xml = o.toString("UTF-8");
            } finally {
                c.disconnect();
            }
            Matcher m = Pattern.compile("<yt:videoId>([\\w-]{11})</yt:videoId>[\\s\\S]*?<published>([^<]+)</published>").matcher(xml);
            String best = null, bestAt = "";
            while (m.find()) {
                if (m.group(2).compareTo(bestAt) > 0) {
                    bestAt = m.group(2);
                    best = m.group(1);
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }
}
