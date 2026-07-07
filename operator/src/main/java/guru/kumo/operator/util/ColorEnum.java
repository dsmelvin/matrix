package guru.kumo.operator.util;

public enum ColorEnum {
    // ===== Reset =====
    RESET("\u001B[0m"),

    // ===== Regular (Normal) Colors =====
    BLACK("\u001B[0;30m"),
    RED("\u001B[0;31m"),
    GREEN("\u001B[0;32m"),
    YELLOW("\u001B[0;33m"),
    BLUE("\u001B[0;34m"),
    PURPLE("\u001B[0;35m"),
    CYAN("\u001B[0;36m"),
    WHITE("\u001B[0;37m"),

    // ===== Bold Colors =====
    BOLD_BLACK("\u001B[1;30m"),
    BOLD_RED("\u001B[1;31m"),
    BOLD_GREEN("\u001B[1;32m"),
    BOLD_YELLOW("\u001B[1;33m"),
    BOLD_BLUE("\u001B[1;34m"),
    BOLD_PURPLE("\u001B[1;35m"),
    BOLD_CYAN("\u001B[1;36m"),
    BOLD_WHITE("\u001B[1;37m"),

    // ===== Underline Colors =====
    UNDERLINE_BLACK("\u001B[4;30m"),
    UNDERLINE_RED("\u001B[4;31m"),
    UNDERLINE_GREEN("\u001B[4;32m"),
    UNDERLINE_YELLOW("\u001B[4;33m"),
    UNDERLINE_BLUE("\u001B[4;34m"),
    UNDERLINE_PURPLE("\u001B[4;35m"),
    UNDERLINE_CYAN("\u001B[4;36m"),
    UNDERLINE_WHITE("\u001B[4;37m"),

    // ===== Background Colors =====
    BG_BLACK("\u001B[40m"),
    BG_RED("\u001B[41m"),
    BG_GREEN("\u001B[42m"),
    BG_YELLOW("\u001B[43m"),
    BG_BLUE("\u001B[44m"),
    BG_PURPLE("\u001B[45m"),
    BG_CYAN("\u001B[46m"),
    BG_WHITE("\u001B[47m"),

    // ===== High Intensity Colors =====
    HIGH_INTENSITY_BLACK("\u001B[0;90m"),
    HIGH_INTENSITY_RED("\u001B[0;91m"),
    HIGH_INTENSITY_GREEN("\u001B[0;92m"),
    HIGH_INTENSITY_YELLOW("\u001B[0;93m"),
    HIGH_INTENSITY_BLUE("\u001B[0;94m"),
    HIGH_INTENSITY_PURPLE("\u001B[0;95m"),
    HIGH_INTENSITY_CYAN("\u001B[0;96m"),
    HIGH_INTENSITY_WHITE("\u001B[0;97m"),

    // ===== Bold High Intensity Colors =====
    BOLD_HIGH_INTENSITY_BLACK("\u001B[1;90m"),
    BOLD_HIGH_INTENSITY_RED("\u001B[1;91m"),
    BOLD_HIGH_INTENSITY_GREEN("\u001B[1;92m"),
    BOLD_HIGH_INTENSITY_YELLOW("\u001B[1;93m"),
    BOLD_HIGH_INTENSITY_BLUE("\u001B[1;94m"),
    BOLD_HIGH_INTENSITY_PURPLE("\u001B[1;95m"),
    BOLD_HIGH_INTENSITY_CYAN("\u001B[1;96m"),
    BOLD_HIGH_INTENSITY_WHITE("\u001B[1;97m"),

    // ===== High Intensity Backgrounds =====
    BG_HIGH_INTENSITY_BLACK("\u001B[0;100m"),
    BG_HIGH_INTENSITY_RED("\u001B[0;101m"),
    BG_HIGH_INTENSITY_GREEN("\u001B[0;102m"),
    BG_HIGH_INTENSITY_YELLOW("\u001B[0;103m"),
    BG_HIGH_INTENSITY_BLUE("\u001B[0;104m"),
    BG_HIGH_INTENSITY_PURPLE("\u001B[0;105m"),
    BG_HIGH_INTENSITY_CYAN("\u001B[0;106m"),
    BG_HIGH_INTENSITY_WHITE("\u001B[0;107m"),

    // ===== Extended Colors (256-color palette, foreground) =====
    ORANGE("\u001B[38;5;208m"),
    PINK("\u001B[38;5;213m"),
    HOT_PINK("\u001B[38;5;198m"),
    GOLD("\u001B[38;5;220m"),
    BROWN("\u001B[38;5;94m"),
    LIME("\u001B[38;5;154m"),
    TEAL("\u001B[38;5;30m"),
    NAVY("\u001B[38;5;17m"),
    INDIGO("\u001B[38;5;54m"),
    VIOLET("\u001B[38;5;177m"),
    MAROON("\u001B[38;5;88m"),
    OLIVE("\u001B[38;5;100m"),
    SKY_BLUE("\u001B[38;5;117m"),
    TURQUOISE("\u001B[38;5;80m"),
    CORAL("\u001B[38;5;203m"),
    SALMON("\u001B[38;5;209m"),
    LAVENDER("\u001B[38;5;183m"),
    BEIGE("\u001B[38;5;230m"),
    LIGHT_GRAY("\u001B[38;5;250m"),
    GRAY("\u001B[38;5;244m"),
    DARK_GRAY("\u001B[38;5;238m"),

    // ===== Extended Colors (256-color palette, background) =====
    BG_ORANGE("\u001B[48;5;208m"),
    BG_PINK("\u001B[48;5;213m"),
    BG_HOT_PINK("\u001B[48;5;198m"),
    BG_GOLD("\u001B[48;5;220m"),
    BG_BROWN("\u001B[48;5;94m"),
    BG_LIME("\u001B[48;5;154m"),
    BG_TEAL("\u001B[48;5;30m"),
    BG_NAVY("\u001B[48;5;17m"),
    BG_INDIGO("\u001B[48;5;54m"),
    BG_VIOLET("\u001B[48;5;177m"),
    BG_MAROON("\u001B[48;5;88m"),
    BG_OLIVE("\u001B[48;5;100m"),
    BG_SKY_BLUE("\u001B[48;5;117m"),
    BG_TURQUOISE("\u001B[48;5;80m"),
    BG_CORAL("\u001B[48;5;203m"),
    BG_SALMON("\u001B[48;5;209m"),
    BG_LAVENDER("\u001B[48;5;183m"),
    BG_BEIGE("\u001B[48;5;230m"),
    BG_LIGHT_GRAY("\u001B[48;5;250m"),
    BG_GRAY("\u001B[48;5;244m"),
    BG_DARK_GRAY("\u001B[48;5;238m"),

    // ===== Blink =====
    // Note: many modern terminals (iTerm2, VS Code, Windows Terminal) ignore
    // blink codes by default for accessibility/readability reasons.
    // Test in your target terminal before relying on it.
    BLINK_SLOW("\u001B[5m"),   // ~150 blinks/min, widely supported (when honored)
    BLINK_RAPID("\u001B[6m"),  // faster blink, rarely supported

    // ===== Blinking Colors (color + slow blink combined) =====
    BLINK_RED("\u001B[5;31m"),
    BLINK_GREEN("\u001B[5;32m"),
    BLINK_YELLOW("\u001B[5;33m"),
    BLINK_BLUE("\u001B[5;34m"),
    BLINK_PURPLE("\u001B[5;35m"),
    BLINK_CYAN("\u001B[5;36m"),
    BLINK_WHITE("\u001B[5;37m"),
    BLINK_BOLD_RED("\u001B[5;1;101m"),
    BG_BLINK_YELLOW("\u001B[5;43m");

    private final String code;

    ColorEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * Wraps the given text with this color's escape code and a reset code,
     * so only this text is colored and normal output resumes afterward.
     */
    public String colorize(String text) {
        return code + text + RESET.code;
    }

    @Override
    public String toString() {
        return code;
    }

    private static int ColorEnumIndex = 0;
    private static final ColorEnum[] colorEnums = {PINK, HOT_PINK, GOLD, BROWN, LIME, TEAL, NAVY, INDIGO, VIOLET, MAROON, OLIVE, SKY_BLUE, TURQUOISE, CORAL, SALMON, LAVENDER, BEIGE, LIGHT_GRAY, GRAY, DARK_GRAY};

    public static ColorEnum getRandomColor() {
        if (ColorEnumIndex == 0) ColorEnumIndex = colorEnums.length - 1;
        return colorEnums[ColorEnumIndex--];
    }

    // ===== Demo =====
    public static void main(String[] args) {
        System.out.println(ColorEnum.RED.colorize("Regular Red"));
        System.out.println(ColorEnum.BOLD_GREEN.colorize("Bold Green"));
        System.out.println(ColorEnum.UNDERLINE_BLUE.colorize("Underline Blue"));
        System.out.println(ColorEnum.BG_YELLOW.colorize("Background Yellow"));
        System.out.println(ColorEnum.HIGH_INTENSITY_CYAN.colorize("High Intensity Cyan"));
        System.out.println(ColorEnum.BOLD_HIGH_INTENSITY_PURPLE.colorize("Bold High Intensity Purple"));
        System.out.println(ColorEnum.BG_HIGH_INTENSITY_WHITE.colorize("BG High Intensity White"));
        System.out.println(ColorEnum.ORANGE.colorize("Extended Orange"));
        System.out.println(ColorEnum.TEAL.colorize("Extended Teal"));
        System.out.println(ColorEnum.LAVENDER.colorize("Extended BG Lavender"));
    }
}
