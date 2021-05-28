package peer;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class UnicastDispatcher implements Runnable {
    private SSLServerSocket socket;
    private ExecutorService pool = Executors.newFixedThreadPool(10);
    private int peerId;
    private Chord chord;

    public UnicastDispatcher(int port, int peerId, Chord chord) {
        this.peerId = peerId;
        this.chord = chord;
        try {
            this.socket = (SSLServerSocket) SSLServerSocketFactory.getDefault().createServerSocket(port);
        } catch (IOException e) {
            System.out.println("Failed to create SSL socket!");
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        SSLSocket clientSocket;

        while (true) {
            try {
                clientSocket = (SSLSocket) this.socket.accept();
                this.pool.execute(new PreHandler(clientSocket, this.peerId, this.chord));
            } catch (IOException e) {
                System.out.println("Failed to accept a new connection!");
                e.printStackTrace();
            }

        }

    }


}