import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import static java.lang.System.exit;

class Client{
    long outputNum = 0;

    public Client(String name, int portNum, int time) {
        try{
            Socket client = new Socket(name, portNum);
            OutputStream output = client.getOutputStream();
            long realtime = (long)time*1000000000;
            byte[] bytes = new byte[1000];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = 0;
            }
            long start = System.nanoTime();
            long end = 0;
            while (System.nanoTime()-start < realtime) {
                output.write(bytes);
                outputNum ++;
            }
            output.close();
            end = System.nanoTime();
            client.close();

            double bandwidth = 1000000.0*outputNum/(end - start); // in Mb
            System.out.println("sent=" + outputNum + " KB rate="+bandwidth*8.0 + " Mbps");

        }catch (IOException e){
            e.printStackTrace();
        }

    }
}
class Server{
    long dataR = 0;

    public Server(int portNum){
        try {
            ServerSocket server = new ServerSocket(portNum);
            Socket client = server.accept();
            //server.setSoTimeout(timeout);
            InputStream input = client.getInputStream();
            long start = System.nanoTime();

            byte[] bytes = new byte[1000];
            while (!server.isClosed()) {
                int numB = input.read(bytes);
                if(numB == -1)
                    break;
                else
                    dataR += numB;
            }
            input.close();
            long end = System.nanoTime();
            client.close();
            server.close();

            double bandwidth = 1000.0*dataR/(end-start);
	        System.out.println("received=" + dataR/1000 + " KB rate=" + bandwidth*8.0 + " Mbps");
        } catch (IOException e){
            e.printStackTrace();
        }
    }
}

public class Iperfer {

    public static void main(String[] args) throws Exception {
        if(args[0].equals("-c")){
            //clientmode
            if (args.length == 7 && args[1].equals("-h") && args[3].equals("-p") && args[5].equals("-t")){
                int p = 0,t = 0;
                try{p = Integer.parseInt(args[4]); t = Integer.parseInt(args[6]);} catch (Exception e){exit(1);}
                if (p >65536 || p < 1024){
                	System.out.print("Error: port number must be in range 1024 to 65535\n");
                    exit(1);
                }
                new Client(args[2],p,t);
            } else {
            	System.out.print("Error: Invalid arguments\n");
                exit(1);
            }
        } else if(args[0].equals("-s")){
            // servermode
            if (args.length == 3 && args[1].equals("-p")){
                int p = 0;
                try {
                    p = Integer.parseInt(args[2]);
                }catch (Exception e){
                    e.printStackTrace();
                    exit(1);
                }
                if (p >65536 || p < 1024){
                	System.out.print("Error: port number must be in range 1024 to 65535\n");
                    exit(1);
                }
                new Server(p);
            }else {
            	System.out.print("Error: Invalid arguments\n");
            	exit(1);
            }
        }

    }
}
