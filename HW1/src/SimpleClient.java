import java.io.InputStream;
import java.net.Socket;

public class SimpleClient {
    public static void main(String[] args) throws Exception {
        Socket socket = new Socket("127.0.0.1", 6666);
        InputStream input = socket.getInputStream();
        long dataR = 0;
        long start = System.currentTimeMillis();

        byte[] bytes = new byte[10240]; // 10K
        while (true) {
            int numB = input.read(bytes);
            dataR += numB;
            long end = System.currentTimeMillis();
            if (end -start > 0 && System.currentTimeMillis() % 10 == 0) {
                System.out.println("Read " + dataR + " bytes, speed: " + dataR / (end-start) + "KB/s");
            }
        }
    }
}
