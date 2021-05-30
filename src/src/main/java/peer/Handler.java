package peer;

import peer.tcp.TCPReader;
import peer.tcp.TCPWriter;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Used as a worker, processes the received messages and sends the response
 */
public class Handler {
    private byte[] message;
    private int peerId;
    private Chord chord;
    private Address address;
    private ConcurrentHashMap<String, File> localFiles;
    private ConcurrentHashMap<String, File> localCopies;
    private AtomicLong maxSize;
    private AtomicLong currentSize;

    Handler(byte[] message, int peerId, Chord chord, Address address, ConcurrentHashMap<String, File> localFiles, ConcurrentHashMap<String, File> localCopies, AtomicLong maxSize, AtomicLong currentSize) {
        this.message = message;
        this.peerId = peerId;
        this.chord = chord;
        this.address = address;
        this.localFiles = localFiles;
        this.localCopies = localCopies;
        this.maxSize = maxSize;
        this.currentSize = currentSize;
    }

    public void processMessage() {
        String stringMessage = new String(this.message);
        if (stringMessage.startsWith("CHORD")) {
            this.chord.processMessage(this.message);
            return;
        }
        System.out.println(stringMessage);

        Header headers = HeaderConcrete.getHeaders(new String(this.message));
        if (headers.getSender() == this.peerId) {
            return;
        }
        switch (headers.getMessageType()) {
            case GETFILE: {
                new GetFileHandler(this.peerId, headers.getFileID(), headers.getAddress(), headers.getPort(), this.localCopies, this.chord);
                break;
            }
            case PUTFILE: {
                new PutFileHandler(this.chord, headers.getFileID(), this.peerId, headers.getReplicationDeg(), this.address, new Address(headers.getAddress(), headers.getPort()), this.localFiles, this.localCopies);
                break;
            }
            case DELETE: {
                new DeleteHandler(this.peerId, headers.getFileID(), headers.getReplicationDeg(), this.localCopies, this.chord);
                break;
            }
        }
    }
}

class PutFileHandler {
    private String fileId;
    private Address local, remote;
    private int peerId, repDegree;
    private Chord chord;
    private ConcurrentHashMap<String, File> localCopies, localFiles;

    public PutFileHandler(Chord chord, String fileId, int peerId, int repDegree, Address localAddress, Address remoteAddress, ConcurrentHashMap<String, File> localFiles, ConcurrentHashMap<String, File> localCopies) {
        this.fileId = fileId;
        this.peerId = peerId;
        this.local = localAddress;
        this.remote = remoteAddress;
        this.chord = chord;
        this.repDegree = repDegree;
        this.localCopies = localCopies;
        this.localFiles = localFiles;
        if (localCopies.containsKey(fileId)) {
            return;
        }
        File f = null;
        try {
            f = this.Receive();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            localCopies.put(f.getFileId(), f);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void PropagateSend(byte[] data, int replicationDegree) throws IOException {
        Node successor = this.chord.getSuccessor();
        TCPWriter messageWriter = new TCPWriter(successor.address.address, successor.address.port);

        SSLServerSocket s = (SSLServerSocket) SSLServerSocketFactory.getDefault().createServerSocket(0);
        s.setNeedClientAuth(true);

        System.out.println("Propagating with RepDegree: " + replicationDegree);

        byte[] contents = MessageType.createPutFile(this.peerId, this.fileId, this.local.address, Integer.toString(s.getLocalPort()), replicationDegree);
        messageWriter.write(contents);

        s.accept().getOutputStream().write(data);
    }

    private File Receive() throws IOException {
        if (this.repDegree > 0) {
            TCPReader reader = new TCPReader(this.remote.address, this.remote.port);

            int bytesRead;
            InputStream in;
            int bufferSize = 0;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean shallWrite = !this.localFiles.containsKey(this.fileId);

            File file = new File(this.fileId, String.valueOf(this.peerId), null, bufferSize, this.repDegree);
            try {
                bufferSize = reader.getSocket().getReceiveBufferSize();
                in = reader.getSocket().getInputStream();
                DataInputStream clientData = new DataInputStream(in);
                OutputStream output = null;
                if (shallWrite)
                    output = new FileOutputStream(this.peerId + "/stored/" + this.fileId);
                byte[] buffer = new byte[bufferSize];
                int read;
                clientData.readLong();
                out.write(new byte[8]);
                while ((read = clientData.read(buffer)) != -1) {
                    if (shallWrite)
                        output.write(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (!shallWrite) {
                System.out.println("propagate");
                this.PropagateSend(out.toByteArray(), this.repDegree);
                return this.localFiles.get(this.fileId);
            } else if (this.repDegree > 1) {
                this.PropagateSend(out.toByteArray(), this.repDegree - 1);
            }
            return file;
        }
        return null;
    }
}


class GetFileHandler {

    private String fileId;
    private String address;
    private int port;
    private ConcurrentHashMap<String, File> localCopies;
    private int peerId;
    private Chord chord;

    public GetFileHandler(int peerId, String fileId, String address, int port, ConcurrentHashMap<String, File> localCopies, Chord chord) {
        this.fileId = fileId;
        this.address = address;
        this.port = port;
        this.localCopies = localCopies;
        this.peerId = peerId;
        this.chord = chord;

        if (!this.hasCopy()) {
            this.resendMessage();
        } else {
            this.sendFile();
        }
    }

    public boolean hasCopy() {
        return this.localCopies.containsKey(this.fileId);
    }

    public void resendMessage() {
        Node successor = this.chord.getSuccessor();
        TCPWriter tcpWriter = new TCPWriter(successor.address.address, successor.address.port);
        byte[] contents = MessageType.createGetFile(this.peerId, this.fileId, this.address, Integer.toString(this.port));
        tcpWriter.write(contents);
    }

    public void sendFile() {
        TCPWriter tcpWriter = new TCPWriter(this.address, this.port);
        Path file = Path.of(this.peerId + "/" + "stored" + "/" + this.fileId);
        try {
            byte[] contents = Files.readAllBytes(file);
            tcpWriter.write(contents);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class DeleteHandler {

    private String fileId;
    private int replicationDegree;
    private ConcurrentHashMap<String, File> localCopies;
    private int peerId;
    private Chord chord;

    public DeleteHandler(int peerId, String fileId, int replicationDegree, ConcurrentHashMap<String, File> localCopies, Chord chord) {
        this.fileId = fileId;
        this.replicationDegree = replicationDegree;
        this.localCopies = localCopies;
        this.peerId = peerId;
        this.chord = chord;
        System.out.println("Deleting");

        if (!this.hasCopy()) {
            this.resendMessage();
        } else {
            this.deleteFile();
            if (this.replicationDegree > 0) {
                this.resendMessage();
            }
        }
    }

    public boolean hasCopy() {
        return this.localCopies.containsKey(this.fileId);
    }

    public void resendMessage() {
        Node successor = this.chord.getSuccessor();
        TCPWriter tcpWriter = new TCPWriter(successor.address.address, successor.address.port);
        byte[] contents = MessageType.createDelete(this.peerId, this.fileId, this.replicationDegree);
        tcpWriter.write(contents);
    }

    public void deleteFile() {
        this.localCopies.get(this.fileId).deleteFile();
        this.replicationDegree--;
        this.localCopies.remove(this.fileId);
    }

}