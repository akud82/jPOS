/**
 * ISOMsgPanel
 * Swing based GUI to ISOMsg
 * @author apr@cs.com.uy
 * @see uy.com.cs.jpos.iso.ISOMsg
 */

/*
 * $Log$
 * Revision 1.1  1999/05/18 12:02:58  apr
 * Added GUI package
 *
 */

package uy.com.cs.jpos.iso.gui;

/**
 * allows for easy visualization of channel utilization. 
 * It shows messages coming through in an
 * 'Oscilloscope' style clickeable window.
 * @see ISOMeter
 * @see ISOMsgPanel
 */
import java.util.Observer;
import java.util.Observable;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;

import uy.com.cs.jpos.iso.*;

public class ISOChannelPanel extends JPanel implements Observer {
	ISOMeter meter;
	DefaultListModel log;
	String symbolicName;

	public ISOChannelPanel
		(ISOChannel channel, String symbolicName) {
		super();
		this.symbolicName = symbolicName;
		setLayout(new FlowLayout());
		setBorder(BorderFactory.createRaisedBevelBorder());
		log = new DefaultListModel();
		add(createCountersPanel());
		meter.setConnected(channel.isConnected());
		channel.addObserver(this);
	}
	public final String getSymbolicName() {
		return symbolicName;
	}
	public final ListModel getLog() {
		return log;
	}

	public void update(Observable o, Object arg) {
		ISOChannel c = (ISOChannel) o;
		if (arg != null && arg instanceof ISOMsg) {
			ISOMsg m = (ISOMsg) arg;
			try {
				String mti = (String) m.getValue(0);
				int imti   = Integer.parseInt(mti);
				if (m.isIncoming())
					meter.setValue(-imti, mti);
				else if (m.isOutgoing())
					meter.setValue(imti, mti);

				log.insertElementAt(m,0);
			} catch (ISOException e) { 
				meter.setValue(ISOMeter.mass, "ERROR");
			}
			meter.setValue(ISOMeter.mass);
		}
		meter.setConnected(c.isConnected());
		int cnt[] = c.getCounters();
		try {
			meter.setPositiveCounter(
				ISOUtil.zeropad(Integer.toString(cnt[ISOChannel.TX]), 6)
			);
			meter.setNegativeCounter(
				ISOUtil.zeropad(Integer.toString(cnt[ISOChannel.RX]), 6)
			);
		} catch (ISOException e) { }
	}

	private JPanel createCountersPanel() {
		JPanel A = new JPanel() {
			public Insets getInsets() {
				return new Insets(10,10,10,10);
			}
		};

		A.setLayout(new BorderLayout());

		meter = new ISOMeter(this);

		JLabel l = new JLabel(symbolicName);
		A.add(l, BorderLayout.NORTH);
		A.add(meter, BorderLayout.CENTER);
		meter.start();
		return A;
	}
	public ISOMeter getISOMeter() {
		return meter;
	}
}
