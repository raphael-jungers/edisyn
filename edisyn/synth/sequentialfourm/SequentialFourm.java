/**
   Librarian support for the Sequential Fourm synthesizer.

   MIDI Implementation reference: Fourm-MIDI-Implementation-Document_V1.1.pdf

   SysEx format:
     Manufacturer ID: 0x01 (Sequential)
     Device ID:       0x3B (Fourm)

   Program data is 4103 raw bytes, packed to 4690 MIDI bytes using the
   Sequential "packed MS bit" format (8-byte packets: 1 MS-bit byte + 7 data bytes).

   Name offset 88 (NRPN 89-108 → bytes 88-107) verified with hardware.
*/

package edisyn.synth.sequentialfourm;

import edisyn.*;
import edisyn.gui.*;
import java.awt.*;
import javax.swing.*;
import java.awt.event.*;
import java.io.*;
import javax.sound.midi.*;

public class SequentialFourm extends Synth
    {
    static final byte FOURM_ID = 0x3B;
    static final int NAME_OFFSET = 88;   // NRPN 89-108 → bytes 88-107; verified with hardware
    static final int NAME_LENGTH = 20;
    static final int DATA_LENGTH = 4103; // raw bytes in a program dump

    static final String[] ALL_BANKS = { "U1", "U2", "F1", "F2" };
    static final String[] WRITEABLE_BANKS = { "U1", "U2" };

    public SequentialFourm()
        {
        model.set("name", "Init");
        model.set("bank", 0);
        model.set("number", 0);
        }

    public static String getSynthName()
        {
        return "Sequential Fourm";
        }

    public String getDefaultResourceFileName()
        {
        return null;
        }

    public String getHTMLResourceFileName()
        {
        return "SequentialFourm.html";
        }

    // ---- Patch location ----

    public String getPatchLocationName(Model model)
        {
        if (!model.exists("bank") || !model.exists("number")) return null;
        int bank = model.get("bank", 0);
        int num = model.get("number", 0) + 1;
        String numStr = (num < 10 ? "00" : num < 100 ? "0" : "") + num;
        return ALL_BANKS[bank] + "-" + numStr;
        }

    public Model getNextPatchLocation(Model model)
        {
        int bank = model.get("bank", 0);
        int num  = model.get("number", 0);
        num++;
        if (num > 127) { num = 0; bank = (bank + 1) % ALL_BANKS.length; }
        Model next = buildModel();
        next.set("bank", bank);
        next.set("number", num);
        return next;
        }

    public boolean patchLocationEquals(Model patch1, Model patch2)
        {
        return patch1.get("bank", 0) == patch2.get("bank", 0) &&
               patch1.get("number", 0) == patch2.get("number", 0);
        }

    // ---- Patch name ----

    public String getPatchName(Model model)
        {
        return model.get("name", "Init");
        }

    public String revisePatchName(String name)
        {
        name = super.revisePatchName(name);
        if (name.length() > NAME_LENGTH)
            name = name.substring(0, NAME_LENGTH);
        StringBuilder sb = new StringBuilder(NAME_LENGTH);
        for (int i = 0; i < name.length(); i++)
            {
            char c = name.charAt(i);
            sb.append((c >= 32 && c < 127) ? c : ' ');
            }
        while (sb.length() < NAME_LENGTH) sb.append(' ');
        return sb.toString();
        }

    // ---- Patch I/O ----

    public boolean gatherPatchInfo(String title, Model changeThis, boolean writing)
        {
        String[] banks = writing ? WRITEABLE_BANKS : ALL_BANKS;
        JComboBox<String> bank = new JComboBox<>(banks);
        bank.setSelectedIndex(writing
            ? Math.min(changeThis.get("bank", 0), WRITEABLE_BANKS.length - 1)
            : changeThis.get("bank", 0));
        int num = changeThis.get("number", 0) + 1;
        JTextField number = new SelectedTextField(
            (num < 10 ? "00" : num < 100 ? "0" : "") + num, 3);

        while (true)
            {
            boolean result = showMultiOption(this,
                new String[]{ "Bank", "Patch Number" },
                new JComponent[]{ bank, number },
                title, "Enter the Bank and Patch Number");
            if (!result) return false;

            int n;
            try { n = Integer.parseInt(number.getText()); }
            catch (NumberFormatException e)
                {
                showSimpleError(title, "The Patch Number must be an integer 1...128");
                continue;
                }
            if (n < 1 || n > 128)
                {
                showSimpleError(title, "The Patch Number must be an integer 1...128");
                continue;
                }
            n--;
            changeThis.set("bank", bank.getSelectedIndex());
            changeThis.set("number", n);
            return true;
            }
        }

    public void changePatch(Model tempModel)
        {
        int bank = tempModel.get("bank", 0);
        int num  = tempModel.get("number", 0);
        tryToSendMIDI(new Object[]{
            buildCC(getChannelOut(), 0, bank)[0],   // Bank Select MSB
            buildCC(getChannelOut(), 32, bank)[0],  // Bank Select LSB
            buildPC(getChannelOut(), num)[0]         // Program Change
            });
        }

    public int getPauseAfterChangePatch()
        {
        return 200;
        }

    public byte[] requestDump(Model tempModel)
        {
        return new byte[]{
            (byte)0xF0,
            (byte)0x01,
            FOURM_ID,
            (byte)0x05,   // Request Program Transmit
            (byte)tempModel.get("bank", 0),
            (byte)tempModel.get("number", 0),
            (byte)0xF7
            };
        }

    public byte[] requestCurrentDump()
        {
        return new byte[]{
            (byte)0xF0,
            (byte)0x01,
            FOURM_ID,
            (byte)0x06,   // Request Program Edit Buffer Transmit
            (byte)0xF7
            };
        }

    public int parse(byte[] data, boolean fromFile)
        {
        if (data[3] == 0x02)  // Program Data Dump (includes bank+prog)
            {
            model.set("bank", data[4] & 0x03);
            model.set("number", data[5] & 0x7F);
            }

        int offset = (data[3] == 0x02) ? 6 : 4;
        byte[] raw = unpackFrom7Bit(data, offset);

        for (int i = 0; i < raw.length; i++)
            model.set("b" + i, raw[i] & 0xFF);

        if (raw.length >= NAME_OFFSET + NAME_LENGTH)
            {
            byte[] nameBytes = new byte[NAME_LENGTH];
            System.arraycopy(raw, NAME_OFFSET, nameBytes, 0, NAME_LENGTH);
            try { model.set("name", new String(nameBytes, "US-ASCII").trim()); }
            catch (UnsupportedEncodingException e) { Synth.handleException(e); }
            }

        revise();
        return PARSE_SUCCEEDED;
        }

    public byte[] emit(Model tempModel, boolean toWorkingMemory, boolean toFile)
        {
        if (tempModel == null) tempModel = getModel();

        // Reassemble raw bytes from model
        byte[] raw = new byte[DATA_LENGTH];
        for (int i = 0; i < DATA_LENGTH; i++)
            raw[i] = (byte) model.get("b" + i, 0);

        // Write name into raw bytes
        String name = (model.get("name", "Init") + "                    ").substring(0, NAME_LENGTH);
        for (int i = 0; i < NAME_LENGTH; i++)
            raw[NAME_OFFSET + i] = (byte)(name.charAt(i) & 0x7F);

        byte[] packed = packTo7Bit(raw);

        if (toWorkingMemory)
            {
            byte[] msg = new byte[4 + packed.length + 1];
            msg[0] = (byte)0xF0;
            msg[1] = (byte)0x01;
            msg[2] = FOURM_ID;
            msg[3] = (byte)0x03; // Edit Buffer Data Dump
            System.arraycopy(packed, 0, msg, 4, packed.length);
            msg[msg.length - 1] = (byte)0xF7;
            return msg;
            }
        else
            {
            byte[] msg = new byte[6 + packed.length + 1];
            msg[0] = (byte)0xF0;
            msg[1] = (byte)0x01;
            msg[2] = FOURM_ID;
            msg[3] = (byte)0x02; // Program Data Dump
            msg[4] = (byte) tempModel.get("bank", 0);
            msg[5] = (byte) tempModel.get("number", 0);
            System.arraycopy(packed, 0, msg, 6, packed.length);
            msg[msg.length - 1] = (byte)0xF7;
            return msg;
            }
        }

    public Object[] emitAll(String key)
        {
        // bank and number are not directly emittable as NRPN
        if (key.equals("bank") || key.equals("number") || key.startsWith("b")) return new Object[0];
        return super.emitAll(key);
        }

    // ---- Librarian support ----

    public String[] getPatchNumberNames()
        {
        return buildIntegerNames(128, 1);
        }

    public String[] getBankNames()
        {
        return ALL_BANKS;
        }

    public boolean[] getWriteableBanks()
        {
        return new boolean[]{ true, true, false, false };
        }

    public boolean getSupportsPatchWrites()
        {
        return true;
        }

    public int getPatchNameLength()
        {
        return NAME_LENGTH;
        }

    public boolean librarianTested()
        {
        return false;
        }

    // ---- Pack/unpack (Sequential "packed MS bit" format) ----

    /** Unpack 7-bit-packed sysex data starting at offset (excluding final F7). */
    byte[] unpackFrom7Bit(byte[] data, int offset)
        {
        int packedLen = data.length - offset - 1; // -1 for F7
        int size = packedLen / 8 * 7;
        if (packedLen % 8 > 0) size += packedLen % 8 - 1;
        byte[] out = new byte[size];
        int j = 0;
        for (int i = offset; i < data.length - 1; i += 8)
            {
            for (int x = 0; x < 7; x++)
                {
                if (j + x < out.length)
                    out[j + x] = (byte)(data[i + x + 1] | (((data[i] >>> x) & 1) << 7));
                }
            j += 7;
            }
        return out;
        }

    /** Pack raw bytes into Sequential's 7-bit MIDI format. */
    byte[] packTo7Bit(byte[] data)
        {
        int size = data.length / 7 * 8;
        if (data.length % 7 > 0) size += 1 + data.length % 7;
        byte[] out = new byte[size];
        int j = 0;
        for (int i = 0; i < data.length; i += 7)
            {
            for (int x = 0; x < 7; x++)
                {
                if (i + x < data.length && j + x + 1 < out.length)
                    {
                    out[j + x + 1] = (byte)(data[i + x] & 0x7F);
                    out[j] = (byte)(out[j] | (((data[i + x] >>> 7) & 1) << x));
                    }
                }
            j += 8;
            }
        return out;
        }
    }
