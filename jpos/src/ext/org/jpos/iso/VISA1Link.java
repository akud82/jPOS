/*
 * Copyright (c) 2000 jPOS.org.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *    "This product includes software developed by the jPOS project 
 *    (http://www.jpos.org/)". Alternately, this acknowledgment may 
 *    appear in the software itself, if and wherever such third-party 
 *    acknowledgments normally appear.
 *
 * 4. The names "jPOS" and "jPOS.org" must not be used to endorse 
 *    or promote products derived from this software without prior 
 *    written permission. For written permission, please contact 
 *    license@jpos.org.
 *
 * 5. Products derived from this software may not be called "jPOS",
 *    nor may "jPOS" appear in their name, without prior written
 *    permission of the jPOS project.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  
 * IN NO EVENT SHALL THE JPOS PROJECT OR ITS CONTRIBUTORS BE LIABLE FOR 
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS 
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) 
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING 
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the jPOS Project.  For more
 * information please see <http://www.jpos.org/>.
 */

package org.jpos.iso;

import java.io.IOException;
import java.util.Vector;

import org.jpos.util.LogEvent;
import org.jpos.util.LogSource;
import org.jpos.util.Logger;
import org.jpos.util.Modem;
import org.jpos.util.V24;

/**
 * Implements client-side VISA-1 Link protocol operating in a
 * ISOMUX-like way (you can queue ISORequests)
 *
 * @author apr@cs.com.uy
 * @version $Id$
 * @see ISORequest
 * @see ISOException
 * @see V24
 * @see Modem
 * @see <a href="http://www.frii.com/~jarvi/rxtx/">CommAPI</a>
 */
public class VISA1Link implements LogSource, Runnable
{
    Logger logger;
    String realm;
    Vector txQueue;
    V24 v24;
    Modem mdm;
    int currentState;
    long timeout;
    boolean waitENQ;
    ISOPackager packager;

    public static final byte SOH = 001;
    public static final byte STX = 002;
    public static final byte ETX = 003;
    public static final byte EOT = 004;
    public static final byte ENQ = 005;
    public static final byte ACK = 006;
    public static final byte DLE = 020;
    public static final byte NAK = 025;

    /**
     * @param v24 instance
     * @param mdm Modem instance
     * @param packager custom VISA1 packager
     */
    public VISA1Link (V24 v24, Modem mdm, ISOPackager packager) {
        super();
        txQueue  = new Vector();
        this.v24 = v24;
        this.mdm = mdm;
        this.packager = packager;
        setDefaults();
    }
    /**
     * @param v24 instance
     * @param mdm Modem instance
     * @param packager custom VISA1 packager
     * @param logger a logger
     * @param realm  logger's realm
     */
    public VISA1Link 
        (V24 v24, Modem mdm, ISOPackager packager, Logger logger, String realm)
    {
        super();
        setLogger (logger, realm);
        txQueue  = new Vector();
        this.v24 = v24;
        this.mdm = mdm;
        this.packager = packager;
        setDefaults();
    }
    public Modem getModem () {
        return mdm;
    }
    private void setDefaults() {
        this.timeout  = 60000;
        this.waitENQ  = true;
    }
    public void setLogger (Logger logger, String realm) {
        this.logger = logger;
        this.realm  = realm;
    }
    public String getRealm () {
        return realm;
    }
    public Logger getLogger() {
        return logger;
    }
    /**
     * @param waitENQ true if should wait for ENQ
     */
    public void setWaitENQ (boolean waitENQ) {
        this.waitENQ = waitENQ;
    }
    /**
     * @param timeout (per request)
     */
    public void setTimeout (long timeout) {
        this.timeout = timeout;
    }
    public long getTimeout () {
        return timeout;
    }

    private byte calcLRC (byte[] b) {
        byte chk = ETX;
        for(int i=0; i<b.length; i++)
            chk ^= b[i];
        return chk;
    }

    public void sendPacket (byte[] b, LogEvent evt) throws IOException {
        // avoid multiple calls to v24.send() in order to show
        // the whole frame within one LogEvent
        byte[] frame = new byte [b.length + 3];
        frame[0] = STX;
        System.arraycopy (b, 0, frame, 1, b.length);
        frame[b.length+1] = ETX;
        frame[b.length+2] = calcLRC (b);
        v24.send (frame);
        v24.flushTransmitter();
        evt.addMessage ("<send>"+ISOUtil.dumpString(frame)+"</send>");
    }

    private byte[] receivePacket (long timeout, LogEvent evt) 
        throws IOException
    {
        String end     = "\002\003\004\005\006\025";
        String packet  = v24.readUntil (end, timeout, true);
        String payload = null;
        if (packet.length() > 2 && packet.charAt (packet.length()-1) == ETX) {
            payload = packet.substring (0, packet.length() - 1);
            byte lrc  = calcLRC (payload.getBytes());
            byte[] receivedLrc = new byte[1];
            int c = v24.read (receivedLrc, 2000);
            if (c != 1 || lrc != receivedLrc[0])
                payload = null;
        }
        return payload != null ? payload.getBytes() : null;
    }

    synchronized public byte[] request (byte[] request, LogEvent evt) 
        throws IOException
    {
        String buf;
        byte[] response = null;
        long timeout = this.timeout;
        long start   = System.currentTimeMillis();
        long expire  = start + timeout;
        int state    = waitENQ ? 0 : 1;
        v24.flushReceiver();
        while ( (timeout = (expire - System.currentTimeMillis())) > 0
                        && response == null && mdm.isConnected()) 
        {
            long elapsed = System.currentTimeMillis() - start;
            switch (state) {
                case 0:
                    evt.addMessage ("<enq>" + elapsed + "</enq>");
                    buf = v24.readUntil ("\005", timeout, true);
                    if (buf.endsWith ("\005") )
                        state++;
                    break;
                case 1:
                    evt.addMessage ("<tx>" + elapsed + "</tx>");
                    sendPacket (request, evt);
                    buf = v24.readUntil ("\002\025", timeout, true);
                    if (buf.endsWith ("\002")) 
                        state++;
                    break;
                case 2:
                    evt.addMessage ("<rx>" + elapsed + "</rx>");
                    response = receivePacket (timeout, evt);
                    // response = "AUTOR. 123456".getBytes();
                    v24.send (response == null ? NAK : ACK);
                    break;
            }
        }
        if (mdm.isConnected() && response == null) {
            // v24.send (EOT);
            evt.addMessage ("<eot/>");
        }
        evt.addMessage ("<rx>"+(System.currentTimeMillis() - start)+"</rx>");
        return response;
    }

    /**
     * @param timeout in millis
     * @param evt an optional LogEvent
     * @return VISA-1 packet
     */
    public byte[] receiveRequest (long timeout, LogEvent evt) {
        byte[] request = null;
        try {
            long expired = System.currentTimeMillis() + timeout;
            int i = 0;
            while (System.currentTimeMillis() < expired) {
                // if (i++ % 5 == 0)
                    v24.send (ENQ);
                String buf = v24.readUntil ("\002\004", 1000, true);
                if (buf.endsWith ("\002")) {
                    request = receivePacket (10*1000, evt);
                    v24.send (request == null ? NAK : ACK);
                    if (request != null)
                        break;
                } else if (buf.endsWith ("\004"))
                    mdm.hangup();
            }
        } catch (IOException e) { 
            evt.addMessage (e);
        }
        return request;
    }
    public boolean sendResponse (byte[] b, long timeout, LogEvent evt) {
        boolean rc = false;
        try {
            long expired = System.currentTimeMillis() + timeout;
            int i = 0;
            while (!rc && System.currentTimeMillis() < expired) {
                sendPacket (b, evt);
                String buf = v24.readUntil ("\006\025", timeout, true);
                if (buf != null && buf.endsWith ("\006")) 
                    rc = true;
            }
        } catch (IOException e) { 
            evt.addMessage (e);
        }
        return rc;
    }

    private void doTransceive () throws IOException, ISOException
    {
        Object o = txQueue.firstElement();
        ISOMsg m = null;
        ISORequest r = null;

        LogEvent evt = new LogEvent (this, "VISA1Link.doTransceive");

        if (o instanceof ISORequest) {
            r = (ISORequest) o;
            if (!r.isExpired())  {
                m = r.getRequest();
                r.setTransmitted ();
            }
        } else if (o instanceof ISOMsg) {
            m = (ISOMsg) o;
        }
        if (m != null) {
            evt.addMessage (m);
            m.setPackager (packager);
            byte[] response = request (m.pack(), evt);
            if (r != null) {
                ISOMsg resp = (ISOMsg) m.clone();
                resp.setDirection (ISOMsg.INCOMING);
                resp.set (new ISOField (0, "0110"));
                resp.set (new ISOField (39, "05"));
                if (response != null)
                    resp.unpack (response);
                evt.addMessage (resp);
                r.setResponse (resp);
            }
        }
        Logger.log (evt);
        txQueue.removeElement(o);
        txQueue.trimToSize();
    }

    public void run () {
        for (;;) {
            try {
                for (;;) {
                    v24.setAutoFlushReceiver (true);
                    while (txQueue.size() == 0 || !mdm.isConnected()) {
                        synchronized(this) {
                            this.wait (1000);
                        }
                    }
                    v24.setAutoFlushReceiver (false);
                    doTransceive ();
                }
            } catch (InterruptedException e) { 
                Logger.log (new LogEvent (this, "mainloop", e));
            } catch (Exception e) {
                Logger.log (new LogEvent (this, "mainloop", e));
                try {
                    Thread.sleep (10000); // just in case (repeated Exception)
                } catch (InterruptedException ex) { }
            }
        }
    }

    synchronized public void queue(ISORequest r) {
        txQueue.addElement(r);
        this.notify();
    }
}