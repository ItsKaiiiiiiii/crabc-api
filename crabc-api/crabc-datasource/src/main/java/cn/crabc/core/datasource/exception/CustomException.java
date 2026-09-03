package cn.crabc.core.datasource.exception;

/**
 * 自定义异常结构
 *
 * @author yuqf
 */
public class CustomException extends RuntimeException {

    private int code;
    private String msg;

    public CustomException(int code, String message) {
        // chatView：message 同步给 Throwable（原实现 getMessage() 恒为 null，日志排查困难）
        super(message);
        this.code = code;
        this.msg = message;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
