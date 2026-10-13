package tcl.remote;

import java.io.ByteArrayOutputStream;

/** Just enough protobuf to talk to the TV. */
final class Pb {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Pb varint(int field, int value) {
        if (value == 0) {
            return this;
        }
        key(field, 0);
        long bits = value & 0xffffffffL;
        if (value < 0) {
            bits |= 0xffffffff00000000L;
        }
        uvarint(bits);
        return this;
    }

    Pb text(int field, String value) {
        return raw(field, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    Pb raw(int field, byte[] value) {
        key(field, 2);
        uvarint(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    byte[] done() {
        return out.toByteArray();
    }

    private void key(int field, int wire) {
        uvarint((field << 3) | wire);
    }

    private void uvarint(long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    static byte[] frame(byte[] message) {
        Pb len = new Pb();
        len.uvarint(message.length);
        byte[] prefix = len.done();
        byte[] out = new byte[prefix.length + message.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(message, 0, out, prefix.length, message.length);
        return out;
    }

    static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        boolean hasNext() {
            return pos < data.length;
        }

        int varint() {
            long value = 0;
            int shift = 0;
            while (pos < data.length) {
                int b = data[pos++] & 0xff;
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return (int) value;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalStateException("varint");
                }
            }
            throw new IllegalStateException("varint curto");
        }

        byte[] take(int n) {
            if (n < 0 || pos + n > data.length) {
                throw new IllegalStateException("tamanho");
            }
            byte[] out = new byte[n];
            System.arraycopy(data, pos, out, 0, n);
            pos += n;
            return out;
        }

        /** First varint of a given field, or 0 when the field is absent. */
        static int field(byte[] message, int wanted) {
            Reader reader = new Reader(message);
            while (reader.hasNext()) {
                int tag = reader.varint();
                int field = tag >>> 3;
                int wire = tag & 7;
                if (wire == 0) {
                    int value = reader.varint();
                    if (field == wanted) {
                        return value;
                    }
                } else if (wire == 2) {
                    reader.take(reader.varint());
                } else if (wire == 5) {
                    reader.take(4);
                } else if (wire == 1) {
                    reader.take(8);
                } else {
                    break;
                }
            }
            return 0;
        }

        static byte[] payload(byte[] message, int wanted) {
            Reader reader = new Reader(message);
            while (reader.hasNext()) {
                int tag = reader.varint();
                int field = tag >>> 3;
                int wire = tag & 7;
                if (wire == 0) {
                    reader.varint();
                } else if (wire == 2) {
                    byte[] body = reader.take(reader.varint());
                    if (field == wanted) {
                        return body;
                    }
                } else if (wire == 5) {
                    reader.take(4);
                } else if (wire == 1) {
                    reader.take(8);
                } else {
                    break;
                }
            }
            return null;
        }
    }
}
