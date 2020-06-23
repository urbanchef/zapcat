package org.kjkoster.zapcat.zabbix;

/* This file is part of Zapcat.
 *
 * Zapcat is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * Zapcat is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * Zapcat. If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.kjkoster.zapcat.util.Base64;


/**
 * A daemon thread that waits for and forwards data items to a Zabbix server.
 * 
 * @author Kees Jan Koster &lt;kjkoster@kjkoster.org&gt;
 */
final class Sender extends Thread {
    private static final Logger log = Logger.getLogger(Sender.class.getName());

    private final BlockingQueue<Item> queue;

    private final InetAddress zabbixServer;

    private final int zabbixPort;

    private boolean stopping = false;

    private static final int TIMEOUT = 5 * 1000;

    /**
     * Create a new background sender.
     * 
     * @param queue
     *            The queue to get data items from.
     * @param zabbixServer
     *            The name or IP of the machine to send the data to.
     * @param zabbixPort
     *            The port number on that machine.
     */
    public Sender(final BlockingQueue<Item> queue,
            final InetAddress zabbixServer, final int zabbixPort) {
        super("Zabbix-sender");
        setDaemon(true);

        this.queue = queue;

        this.zabbixServer = zabbixServer;
        this.zabbixPort = zabbixPort;
    }

    /**
     * Indicate that we are about to stop.
     */
    public void stopping() {
        stopping = true;
        interrupt();
    }

    /**
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        while (!stopping) {
            try {
                final Item item = queue.take();

                send(item.getHost(), item.getKey(), item.getValue());
            } catch (InterruptedException e) {
                if (!stopping) {
                    log.log(Level.WARNING, "ignoring exception", e);
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "ignoring exception", e);
            }
        }

        // drain the queue
        while (queue.size() > 0) {
            final Item item = queue.remove();
            try {
                send(item.getHost(), item.getKey(), item.getValue());
            } catch (Exception e) {
                log.log(Level.WARNING, "ignoring exception", e);
            }
        }
    }

    private void send(final String host, final String key, final String value)
            throws IOException {
        final long start = System.currentTimeMillis();

        final String message = buildJSonString(host, key, value);

        log.finest("sending " + message);

        Socket zabbix = null;
        OutputStream out = null;
        BufferedReader in = null;
        try {
            zabbix = new Socket(zabbixServer, zabbixPort);
            zabbix.setSoTimeout(TIMEOUT);

            out = zabbix.getOutputStream();
            writeMessage(out, message.getBytes());
            out.flush();

            in = new BufferedReader(new InputStreamReader(zabbix
                    .getInputStream()));
            final String response = in.readLine();
            
            log.finest("received " + response);
            
            if (!"OK".equals(response)) {
                log.log(Level.WARNING, "received unexpected response '"
                        + new String(response) + "' for key '" + key + "'");
            }
        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
            if (zabbix != null) {
                zabbix.close();
            }
        }

        log.info("send() " + (System.currentTimeMillis() - start) + " ms");
    }

    private String buildJSonString(String host, String item, String value) {
        return 		  "{"
                + "\"request\":\"sender data\",\n"
                + "\"data\":[\n"
                +        "{\n"
                +                "\"host\":\"" + host + "\",\n"
                +                "\"key\":\"" + item + "\",\n"
                +                "\"value\":\"" + value.replace("\\", "\\\\") + "\"}]}\n" ;
    }


    protected void writeMessage(OutputStream out, byte[] data) throws IOException {
        int length = data.length;

        out.write(new byte[] {
                'Z', 'B', 'X', 'D',
                '\1',
                (byte)(length & 0xFF),
                (byte)((length >> 8) & 0x00FF),
                (byte)((length >> 16) & 0x0000FF),
                (byte)((length >> 24) & 0x000000FF),
                '\0','\0','\0','\0'});

        out.write(data);
    }
}
