import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;

import static java.nio.file.Files.readAllBytes;


public class Server {
    static final int port = 23340;
    static final int windowSize = 128;
    static final int timeout = 2000;
    static final String cachePath = "cache/";

    static final int seqSize = 2;
    static final int dataSize = 1024;
    static final int checkSumSize = 2;
    static final int packetSize = seqSize + dataSize + checkSumSize;
    static final boolean dropPackets = false;
    static final int dropPercent = 1;
    static final int maxTimeouts = 3;
    public static void main(String[] args) throws IOException {

        while (true) {
            try {
                //create the server socket
                DatagramSocket serverSocket = new DatagramSocket(null);
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));

                System.out.println("Server started");

                // Wait for a connection
                System.out.println("Waiting for client to connect");
                DatagramPacket packet = new DatagramPacket(new byte[packetSize], packetSize);
                serverSocket.receive(packet);
                InetAddress clientAddress = packet.getAddress();
                int clientPort = packet.getPort();
                System.out.println("Connection from " + clientAddress + ":" + clientPort);
                System.out.println("Client says: " + new String(packet.getData()));

                // say hello to the client
                packet = new DatagramPacket("hello".getBytes(), "hello".getBytes().length, clientAddress, clientPort);
                serverSocket.send(packet);

                // get the KEY
                byte[] keyBytes = new DatagramPacket(new byte[packetSize], packetSize).getData();
                serverSocket.receive(new DatagramPacket(keyBytes, packetSize));
                int key = ByteBuffer.wrap(keyBytes).getInt();
                System.out.println("Key: " + key);

                // get the URL
                byte[] urlBytes = new DatagramPacket(new byte[packetSize], packetSize).getData();
                serverSocket.receive(new DatagramPacket(urlBytes, packetSize));
                String url = new String(urlBytes, StandardCharsets.UTF_8);
                url = url.substring(0, url.indexOf('\0'));
                System.out.println("URL: " + url);

                // check if the file is in the cache, if not get the file from the url
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                fileName = fileName.replace(":", "_");
                Path filePath = Path.of(cachePath + fileName);
                File file = new File(String.valueOf(filePath));

                if (!file.exists()) {
                    System.out.println("File not in cache, fetching from URL...");
                    getAndSaveFile(url, fileName);
                }

                byte[] fileBytes = readAllBytes(filePath);
                System.out.println("File size: " + fileBytes.length + " bytes");
                
                // encrypt the data
                fileBytes = xor(fileBytes, key);
                
                // determine how many frames will be needed to send the file
                int numFrames = (int) Math.ceil((double) fileBytes.length / dataSize);
                System.out.println("Number of frames to send: " + numFrames);

                // create the frames
                ArrayList<byte[]> frames = new ArrayList<>();
                for (int i = 0; i < numFrames; i++) {
                    ByteBuffer buf = ByteBuffer.allocate(seqSize + dataSize + checkSumSize);
                    // add the sequence number, using the first 2 bytes
                    buf.putShort((short) i);
                    // add the data, using the next dataSize worth of bytes
                    if (i < numFrames - 1) {
                        buf.put(fileBytes, i * dataSize, dataSize);
                    } else {
                        buf.put(fileBytes, i * dataSize, fileBytes.length - (i * dataSize));
                    }

                    // add the checksum, using the last 2 bytes
                    byte[] data = new byte[dataSize];
                    buf.position(seqSize);
                    buf.get(data, 0, dataSize);
                    buf.putShort(calculateChecksum(data));
                    // add the frame to the frame array
                    frames.add(buf.array());
                }

                // send the number of frames to the client and wait for an ACK
                serverSocket.send(new DatagramPacket(ByteBuffer.allocate(4).putInt(numFrames).array(), 4, clientAddress, clientPort));
                byte[] numOfFrameBytes = new byte[4];
                serverSocket.receive(new DatagramPacket(numOfFrameBytes, 4));

                // send the file to the client using GBN sliding window |
                System.out.println("Sending file:");
                short seqNumBase = 0;
                byte[] ackBytes = new byte[seqSize];
                Random rand = new Random();
                int timeoutCount = 0;
                while (true) {
                    // send the window
                    for (int i = seqNumBase; i < seqNumBase + windowSize && seqNumBase < numFrames; i++) {
                        // determine if the packet should be dropped
                        if (dropPackets && rand.nextInt(99) < dropPercent) {
                            System.out.println("Dropped packet: " + i );
                        } else {
                            // send the frame
                            serverSocket.send(new DatagramPacket(frames.get(i), frames.get(i).length, clientAddress, clientPort));
                            System.out.println("Sent packet: " + i );
                            // if the last frame is sent, break
                        }
                        if (i == numFrames - 1) {
                            break;
                        }
                    }
                    // receive one or more ACKs
                    try {
                        // set the timeout
                        serverSocket.setSoTimeout(timeout);
                        // read the ack, 2 bytes
                        ackBytes = new byte[seqSize];
                        serverSocket.receive(new DatagramPacket(ackBytes, seqSize));
                        timeoutCount = 0;
                        // convert the ack to a short
                        short ack = ByteBuffer.wrap(ackBytes).getShort();
                        System.out.println(" Received ACK " + ack);
                        // if the ack was in the window, move the window forward to the 1 + ack
                        if (ack >= seqNumBase && ack < seqNumBase + windowSize) {
                            seqNumBase = (short) (ack + 1);
                        }
                        // check if the ack was the final frame
                        if (ack == (numFrames - 1)) {
                            break;
                        }
                    } catch (SocketTimeoutException e) {
                        // if the ack is not received in time resend the window
                        timeoutCount++;
                        System.out.println("Timeout: resending window");
                        if (timeoutCount == maxTimeouts) {
                            System.out.println("Max number of timeouts: closing connection");
                            break;
                        }
                    }
                }

                // close the connection
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // gets the corresponding page/file using HTTP and saves it to the Cache folder
    private static void getAndSaveFile(String url, String fileName) throws IOException {
        URL urlObj = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) urlObj.openConnection();
        connection.setRequestMethod("GET");
        connection.connect();
        // get the file
        InputStream fis = connection.getInputStream();
        // save the file to the Cache folder
        FileOutputStream fos = new FileOutputStream(cachePath + fileName);
        byte[] buffer = new byte[1024];
        int bytesRead = 0;
        while ((bytesRead = fis.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
        }
        fos.close();
    }
    private static byte[] xor(byte[] msg, int key) {
        byte[] xorMsg = new byte[msg.length];
        for (int i = 0; i < msg.length; i++) {
            xorMsg[i] = (byte) (msg[i] ^ key);
        }
        return xorMsg;
    }

    public static short calculateChecksum(byte[] data) {
        // Sum all the bytes in the data portion of the packet and return the one's complement
        short sum = 0;
        for (int i = 0; i < dataSize; i++) {
            sum += data[i];
        }
        return (short) ~sum;
    }
}