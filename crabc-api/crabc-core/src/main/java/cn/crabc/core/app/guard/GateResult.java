package cn.crabc.core.app.guard;

/**
 * 单道闸校验结果
 *
 * @author chatview
 */
public class GateResult {

    public enum Verdict { PASS, REJECT }

    private final String gate;
    private final Verdict verdict;
    private final String message;

    private GateResult(String gate, Verdict verdict, String message) {
        this.gate = gate;
        this.verdict = verdict;
        this.message = message;
    }

    public static GateResult pass(String gate) {
        return new GateResult(gate, Verdict.PASS, null);
    }

    public static GateResult reject(String gate, String message) {
        return new GateResult(gate, Verdict.REJECT, message);
    }

    public String getGate() {
        return gate;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public String getMessage() {
        return message;
    }

    public boolean isPassed() {
        return verdict == Verdict.PASS;
    }

    @Override
    public String toString() {
        return gate + ":" + (isPassed() ? "PASS" : "REJECT(" + message + ")");
    }
}
