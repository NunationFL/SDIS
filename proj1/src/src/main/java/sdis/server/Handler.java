package sdis.server;

import sdis.Server;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public class Handler implements Runnable {
    private static AtomicInteger skipped = new AtomicInteger(0);
    static private long time = System.currentTimeMillis();
    private DatagramPacket packet;
    private int peerId;
    private List<StandbyBackup> standbyBackupList;

    Handler(DatagramPacket packet, int peerId) {
        this.packet = packet;
        this.peerId = peerId;
        standbyBackupList = new ArrayList<>();
    }

    private void checkForStandby(String filedId,int chunkNo){
        for(StandbyBackup standbyBackup:standbyBackupList){
            if(standbyBackup.isEqual(filedId,chunkNo)){
                standbyBackup.cancel();
                return;
            }
        }
    }

    private static String readContent(Path file) {
        AsynchronousFileChannel fileChannel = null;
        try {
            fileChannel = AsynchronousFileChannel.open(
                    file, StandardOpenOption.READ);
        } catch (IOException e) {
            e.printStackTrace();
        }

        ByteBuffer buffer = ByteBuffer.allocate(1024);

        Future<Integer> operation = fileChannel.read(buffer, 0);

        // run other code as operation continues in background
        try {
            operation.get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        String fileContent = new String(buffer.array()).trim();
        buffer.clear();
        return fileContent;
    }

    @Override
    public void run() {
        try {
            String[] head_body = new String(this.packet.getData()).stripLeading().split("\r\n\r\n", 2);
            byte body[] = null;
            byte tmp[] = this.packet.getData();
            int i = 0;
            for (; i < this.packet.getLength() - 3; i++) {
                if (tmp[i] == 0xd && tmp[i + 1] == 0xa && tmp[i + 2] == 0xd && tmp[i + 3] == 0xa) {
                    break;
                }
            }
            i += 4;
            if (head_body.length > 1) {
                if (this.packet.getLength() > i) {
                    body = Arrays.copyOfRange(this.packet.getData(), i, this.packet.getLength());
                }
            }
            List<Header> headers = HeaderConcrete.getHeaders(head_body[0] + "\r\n\r\n");

            for (Header header : headers) {
                if (header.getSenderID() == this.peerId) {
                    return;
                }
                switch (header.getMessageType()) {
                    case PUTCHUNK -> {
                        time = System.currentTimeMillis();

                        if(Server.getServer().getMyFiles().containsKey(header.getFileID())) return;

                        checkForStandby(header.getFileID(),header.getChunkNo());

                        byte m[] = MessageType.createStored(header.getVersion(), this.peerId, header.getFileID(), header.getChunkNo());
                        DatagramPacket packet = new DatagramPacket(m, m.length, Server.getServer().getMc().getAddress(), Server.getServer().getMc().getPort());
                        if (Server.getServer().getMaxSize().get() == -1 || Server.getServer().getCurrentSize().get() + body.length <= Server.getServer().getMaxSize().get()) {
                            if (!Server.getServer().getStoredFiles().containsKey(header.getFileID())) {
                                try {
                                    Files.createDirectories(Paths.get(Server.getServer().getServerName() + "/" + header.getFileID()));
                                } catch (IOException e) {
                                    System.exit(1);
                                }
                                Server.getServer().getStoredFiles().put(header.getFileID(), new RemoteFile(header.getFileID()));
                            }
                            if (!Server.getServer().getStoredFiles().get(header.getFileID()).chunks.containsKey(header.getChunkNo())) {
                                Chunk c = new Chunk(header.getChunkNo(), header.getFileID(), header.getReplicationDeg(), body.length);
                                c.getPeerList().put(peerId, true);
                                c.update("rdata");
                                c.getPeerList().put((int) Server.getServer().getPeerId(),true);

                                Server.getServer().getStoredFiles().get(header.getFileID()).chunks.put(header.getChunkNo(), c);

                                Path path = Paths.get(Server.getServer().getServerName() + "/" + header.getFileID() + "/" + header.getChunkNo());
                                AsynchronousFileChannel fileChannel = null;
                                try {
                                    fileChannel = AsynchronousFileChannel.open(
                                            path, WRITE, CREATE);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }

                                ByteBuffer buffer = ByteBuffer.allocate(body.length);

                                buffer.put(body);
                                buffer.flip();

                                fileChannel.write(buffer, 0);
                                buffer.clear();
                                Server.getServer().getCurrentSize().addAndGet(body.length);
                            }
                        }
                        Server.getServer().getPool().schedule(() -> Server.getServer().getMc().send(packet), new Random().nextInt(401), TimeUnit.MILLISECONDS);
                    }
                    case STORED -> {
                        if (Server.getServer().getMyFiles().containsKey(header.getFileID())) {
                            Server.getServer().getMyFiles().get(header.getFileID()).addStored(header.getChunkNo(), header.getSenderID());
                        } else if (Server.getServer().getStoredFiles().containsKey(header.getFileID()) && Server.getServer().getStoredFiles().get(header.getFileID()).chunks.containsKey(header.getChunkNo())) {
                            Server.getServer().getStoredFiles().get(header.getFileID()).addStored(header.getChunkNo(), header.getSenderID());
                        } else {
                            //System.out.println("Skipped " + header.getFileID() + "/" + header.getChunkNo() + " : " + skipped.getAndIncrement());
                        }
                    }
                    case GETCHUNK -> {

                        if (Server.getServer().getStoredFiles().containsKey(header.getFileID())) {

                            Path name = Path.of(Server.getServer().getServerName() + "/" + header.getFileID() + "/" + header.getChunkNo());
                            if (Files.exists(name)) {
                                try {
                                    byte[] file_content;
                                    file_content = Files.readAllBytes(name);
                                    byte[] message = MessageType.createChunk("1.0", (int) Server.getServer().getPeerId(), header.getFileID(), header.getChunkNo(), file_content);
                                    DatagramPacket packet = new DatagramPacket(message, message.length, Server.getServer().getMdr().getAddress(), Server.getServer().getMdr().getPort());
                                    Server.getServer().getPool().schedule(() -> Server.getServer().getMdr().send(packet), new Random().nextInt(401), TimeUnit.MILLISECONDS);
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    }
                    case DELETE -> {
                        if (Server.getServer().getStoredFiles().containsKey(header.getFileID())) {
                            RemoteFile f = Server.getServer().getStoredFiles().get(header.getFileID());
                            Server.getServer().getStoredFiles().remove(header.getFileID());
                            f.delete();
                        }
                    }
                    case REMOVED -> {
                        System.out.println("REMOVED CHUNK " + header.getChunkNo());
                        Chunk chunk = null;
                        boolean isLocalCopy = false;
                        if (Server.getServer().getMyFiles().containsKey(header.getFileID())) {
                            System.out.println("My Files");
                            chunk = Server.getServer().getMyFiles().get(header.getFileID()).getChunks().get(header.getChunkNo());

                        } else if (Server.getServer().getStoredFiles().containsKey(header.getFileID())) {
                            System.out.println("Remote Files");
                            isLocalCopy = true;
                            chunk = Server.getServer().getStoredFiles().get(header.getFileID()).getChunks().get(header.getChunkNo());

                        }
                        if (chunk != null) {
                            if (!isLocalCopy) {
                                if (chunk.getPeerList().containsKey(header.getSenderID())) {
                                    chunk.getPeerList().remove(header.getSenderID());
                                    this.chunkUpdate(chunk,"ldata");
                                }
                            } else {
                                chunk.subtractRealDegree();
                                this.chunkUpdate(chunk,"rdata");
                            }
                            if(chunk.getRealDegree()<chunk.getRepDegree()){
                                if(isLocalCopy){
                                    Path name = Path.of(Server.getServer().getServerName() + "/" + header.getFileID() + "/" + header.getChunkNo());
                                    if (Files.exists(name)) {
                                        try {
                                            StandbyBackup standbyBackup = new StandbyBackup(header.getFileID(),header.getChunkNo(),false);
                                            standbyBackupList.add(standbyBackup);

                                            Thread.sleep(new Random().nextInt(401));

                                            standbyBackupList.remove(standbyBackup);
                                            if(standbyBackup.isCanceled()) return;
                                            byte[] file_content;
                                            file_content = Files.readAllBytes(name);

                                            byte[] message = MessageType.createPutchunk("1.0", (int) Server.getServer().getPeerId(), header.getFileID(), (int) header.getChunkNo(),chunk.getRepDegree() ,file_content);
                                            DatagramPacket packet = new DatagramPacket(message, message.length, Server.getServer().getMdb().getAddress(), Server.getServer().getMdb().getPort());
                                            System.out.println("SENDING CHUNK NO "+chunk.getChunkNo());
                                            removeAux(1,Server.getServer().getPool(), packet,header.getFileID(),header.getChunkNo(),chunk.getRepDegree());
                                        }
                                        catch (IOException | InterruptedException e){
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }
                        }
                    }
                    case CHUNK -> {
                        if (Server.getServer().getFileRestoring().containsKey(header.getFileID())) {
                            if (!Server.getServer().getFileRestoring().get(header.getFileID()).getChunks().containsKey(header.getChunkNo())) {
                                Server.getServer().getFileRestoring().get(header.getFileID()).getChunks().put(header.getChunkNo(), body);
                            }

                            if (Server.getServer().getFileRestoring().get(header.getFileID()).getNumberOfChunks() == null && body.length < 64000) {
                                Server.getServer().getFileRestoring().get(header.getFileID()).setNumberOfChunks(header.getChunkNo() + 1);
                            }

                            if (Server.getServer().getFileRestoring().get(header.getFileID()).getNumberOfChunks() != null && Server.getServer().getFileRestoring().get(header.getFileID()).getChunks().values().size() == Server.getServer().getFileRestoring().get(header.getFileID()).getNumberOfChunks()) {

                                String folder = Server.getServer().getServerName() + "/" + "restored";
                                if (!Files.exists(Path.of(folder))) {
                                    try {
                                        Files.createDirectories(Path.of(folder));
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                                Path path = Paths.get(folder + "/" + Server.getServer().getMyFiles().get(header.getFileID()).getName());
                                AsynchronousFileChannel fileChannel = null;
                                try {
                                    fileChannel = AsynchronousFileChannel.open(
                                            path, WRITE, CREATE);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }

                                for (int iterator = 0; iterator < Server.getServer().getFileRestoring().get(header.getFileID()).getChunks().size(); iterator++) {
                                    ByteBuffer buffer = ByteBuffer.allocate(Server.getServer().getFileRestoring().get(header.getFileID()).getChunks().get(iterator).length);

                                    buffer.put(Server.getServer().getFileRestoring().get(header.getFileID()).getChunks().get(iterator));
                                    buffer.flip();

                                    fileChannel.write(buffer, iterator * 64000L);
                                    buffer.clear();
                                }
                            }
                        }
                    }
                }
            }
        } catch (ParseError parseError) {
            parseError.printStackTrace();
        }
    }

    private void removeAux(int i, ScheduledExecutorService pool, DatagramPacket packet, String fileId, int chunkNo, int repDegree) {
        Server.getServer().getMdb().send(packet);
        System.out.println("TRYING again "+chunkNo);
        pool.schedule(() -> {
            if (Server.getServer().getMyFiles().get(fileId).getReplicationDegree(chunkNo) < repDegree) {
                if (i < 16) {
                    //System.out.println("Against: " + i + " " + this.agains.getAndIncrement() + " " + chunkNo);
                    this.removeAux(i * 2, pool, packet, fileId, chunkNo, repDegree);
                } else {
                    System.out.println("Gave up on removed backup subprotocol");
                }
            }
        }, i * 1000L + new Random().nextInt(401), TimeUnit.MILLISECONDS);
    }

    private void chunkUpdate(Chunk chunk,String folder) {
        chunk.update(folder);
        if (chunk.getRepDegree() > chunk.getPeerCount()) {
            chunk.getShallSend().set(true);
            Server.getServer().getPool().schedule(() -> {
                if (chunk.getShallSend().get()) {
                    chunk.backup(Server.getServer().getPool());
                }
            }, new Random().nextInt(401), TimeUnit.MILLISECONDS);
        }
    }
}
