package KirizsKeve.MoveByText.nlp;

import java.io.InputStream;
import java.util.Arrays;

/** HTTP helpers shared by the providers. */
public final class Http {

    private Http() {
    }

    /** The whole stream as UTF-8 text, "" for null. Closes the stream. */
    public static String readFully(InputStream in) throws Exception {
        if (in == null) return "";
        try {
            // Starsector's script sandbox blocks most java.io classes (e.g. ByteArrayOutputStream),
            // so grow a plain byte[] by hand instead.
            byte[] data = new byte[8192];
            int len = 0;
            int n;
            while ((n = in.read(data, len, data.length - len)) != -1) {
                len += n;
                if (len == data.length) {
                    data = Arrays.copyOf(data, data.length * 2);
                }
            }
            return new String(data, 0, len, "UTF-8");
        } finally {
            in.close();
        }
    }
}
