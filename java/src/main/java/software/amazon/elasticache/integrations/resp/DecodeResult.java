package software.amazon.elasticache.integrations.resp;

/** Result of decoding a RESP value: the value and the position after it. */
public class DecodeResult {

    private final RespValue value;
    private final int position;

    public DecodeResult(RespValue value, int position) {
        this.value = value;
        this.position = position;
    }

    public RespValue getValue() {
        return value;
    }

    public int getPosition() {
        return position;
    }
}
