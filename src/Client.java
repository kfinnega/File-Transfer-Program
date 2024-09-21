import java.net.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;

public class Client {
    static final int port = 23340;
    static String host = "localhost";
    static String url = "https://assets.website-files.com/5f7b6161a50f4b3194e9063f/5fc40d89e509f33035dae289_artworks-0d362b2c-5062-4ef0-a746-89be54f4cff5-0-t500x500.jpg";
    static String fileName = url.substring(url.lastIndexOf('/') + 1);
    static final int seqSize = 2;
    static final int dataSize = 1024;
    static final int checkSumSize = 2;
    static final int frameSize = seqSize + dataSize + checkSumSize;
    static final boolean dropPackets = false;
    static final int dropPercent = 1;


    public static void main(String[] args) {
        byte[] frame = new byte[frameSize];
        byte[] data = new byte[dataSize];
        byte[] seqNum = new byte[seqSize];
        byte[] ack = new byte[seqSize]; // the ack to send
        byte[] checksum = new byte[checkSumSize]; // the checksum of the data
        ArrayList<byte[]> fileBytes = new ArrayList<>();

        try {
            System.out.println("Client started");
            DatagramSocket clientSocket = new DatagramSocket(); // Create the client socket
            InetAddress address = InetAddress.getByName(host);
            
            // create in and out streams for the clientSocket
            DatagramPacket in = new DatagramPacket(frame, frame.length, address,port); // the in stream
            DatagramPacket out = new DatagramPacket(frame, frame.length, address,port); // the out stream
            
            
            frame = "Hello".getBytes();
            out.setData(frame);
            clientSocket.send(out);
            
            // listen for hello back
            clientSocket.receive(in);
            frame = in.getData();
            System.out.println("Server says: " + new String(frame));
            
            // create and send a random int bounded by the max value of a byte [-128, 127]
            int key = new Random().nextInt(Byte.MAX_VALUE);
            System.out.println("Sending key: " + key);
            frame = ByteBuffer.allocate(4).putInt(key).array();
            out.setData(frame);
            clientSocket.send(out);
            
            // send the URL
            System.out.println("Sending URL: " + url );
            frame = url.getBytes();
            out.setData(frame);
            clientSocket.send(out);
            
            //listen for the number of expected frames and ACK it back
            clientSocket.receive(in);
            int numFrames = ByteBuffer.wrap(in.getData()).getInt();
            frame = ByteBuffer.allocate(4).putInt(numFrames).array();
            out.setData(frame);
            clientSocket.send(out);
            
            // get the file
            short nextSeqNum = 0; // the sequence number of the next frame expected
            int sleepyCount = 0;
            Random rand = new Random();
            long start = System.nanoTime();

            while (!clientSocket.isClosed() && fileBytes.size() < numFrames){
                clientSocket.receive(in);
                frame = in.getData();
                
                // extract the frame
                System.arraycopy(frame, 0, seqNum, 0, seqSize);
                System.arraycopy(frame, seqSize, data, 0, dataSize);
                System.arraycopy(frame, seqSize+dataSize, checksum, 0, checkSumSize);
                System.out.println("Received frame: " + ByteBuffer.wrap(seqNum).getShort());
                
                // frame is expected this and isn't corrupt
                short frameSeqNum = ByteBuffer.wrap(seqNum).getShort();
                short frameChecksum = ByteBuffer.wrap(checksum).getShort();
                if(!isCorrupt(data, frameChecksum) && frameSeqNum == nextSeqNum){
                    //deliver data to fileBytes
                    System.arraycopy(frame, seqSize, data, 0, dataSize);
                    fileBytes.add(data);
                    data = new byte[dataSize];
                    //send ACK of the next sequence number
                    ack = ByteBuffer.allocate(seqSize).putShort(nextSeqNum).array();
                    if (dropPackets && rand.nextInt(99) < dropPercent) {
                        System.out.println("Dropping ACK: " + ByteBuffer.wrap(ack).getShort());
                    } else {
                        out.setData(ack);
                        clientSocket.send(out);
                        System.out.println("Sent ACK: " + ByteBuffer.wrap(ack).getShort());
                    }
                    //increment expected sequence number
                    nextSeqNum++;
                } else {

                    // Drop frame
                    System.out.println("Corrupt or out of order frame: Dropping");
                    // send ACK of the next sequence number
                    if (nextSeqNum == 0) {
                        ack = ByteBuffer.allocate(seqSize).putShort((short) 0).array();
                    } else {
                        ack = ByteBuffer.allocate(seqSize).putShort((short) (nextSeqNum - 1)).array();
                    }
                    if (dropPackets && rand.nextInt(99) < dropPercent) {
                        System.out.println("Dropping ACK: " + ByteBuffer.wrap(ack).getShort() );
                    } else {
                        out.setData(ack);
                        clientSocket.send(out);
                        System.out.println("Sent ACK: " + ByteBuffer.wrap(ack).getShort() );
                    }
                }
            }
          
            long end = System.nanoTime();
            double duration = (double) (end - start)/1_000_000_000; // in seconds
            //System.out.println("Sleepy count: " + sleepyCount);
            System.out.println("File of size " + (fileBytes.size() * dataSize) + " bytes " + "received in " + duration + " seconds");
            // Throughput in Mb/s
            System.out.println("Throughput: " + (((fileBytes.size() * dataSize) * 8) / (duration))/1000000 + " Mb/s");

            //decrypt the file
            for (byte[] bytes : fileBytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) (bytes[i] ^ key);
                }
            }

            // open the image in image viewer
            try {
                Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + fileName);
            } catch (Exception e) {
                e.setStackTrace(e.getStackTrace());
                System.out.println(e);
            }

            // close the connection
            clientSocket.close();

        } catch (Exception e) {
            e.setStackTrace(e.getStackTrace());
            System.out.println(e);
        }

        try {
            FileOutputStream fos = new FileOutputStream(fileName);
            for (byte[] bytes : fileBytes) {
                fos.write(bytes);
            }
            fos.close();
        } catch (Exception e) {
            e.setStackTrace(e.getStackTrace());
            System.out.println(e);
        }
    }

    public static short calculateChecksum(byte[] data) {
        // Sum all the bytes in the data portion of the packet and return the one's complement
        short sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += data[i];
        }
        return (short) ~sum;
    }
    public static boolean isCorrupt(byte[] packet, short checksum) {
        return checksum != calculateChecksum(packet);
    }
}
