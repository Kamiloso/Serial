package page.se3.serial;

import java.lang.reflect.Array;
import java.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.function.*;

import page.se3.serial.SerialCompiler.*;
import page.se3.serial.SerialCompiler.Record;

interface Globals {

    // KEYWORDS
    String RECORD = "RECORD";
    String STRUCT = "STRUCT";
    String DEFINE = "DEFINE";
    String DEF = "DEF";
    String LET = "LET";
    String SET = "SET";
    String NEST = "NEST";
    String BASE = "BASE";
    String ENSURE = "ENSURE";
    Set<String> KEYWORDS = Set.copyOf(Arrays.asList(RECORD, STRUCT, DEFINE, DEF, LET, SET, NEST, BASE, ENSURE));
    Set<String> RECORD_KEYWORDS = Set.copyOf(Arrays.asList(RECORD, STRUCT));
    Set<String> DEFINE_KEYWORDS = Set.copyOf(Arrays.asList(DEFINE, DEF));

    // TYPES
    String BYTE = "byte";
    String SHORT = "short";
    String INT = "int";
    String LONG = "long";
    String FLOAT = "float";
    String DOUBLE = "double";
    String CHAR = "char";
    String BOOLEAN = "boolean";

    // LOGIC
    String AND = "AND";
    String OR = "OR";

    // COMPARING
    String LARGER = ">";
    String LARGER_OR_EQ = ">=";
    String SMALLER = "<";
    String SMALLER_OR_EQ = "<=";
    String EQUALS = "==";
    String NOT_EQUALS = "!=";
    String IS = "IS";
    String MATCHES = "MATCHES";
    String LITERAL_EQUALS = "EQUALS";
    Set<String> VAR_OPERATORS = Set.copyOf(Arrays.asList(LARGER, LARGER_OR_EQ, SMALLER, SMALLER_OR_EQ, EQUALS, NOT_EQUALS, IS));
    Set<String> STR_OPERATORS = Set.copyOf(Arrays.asList(MATCHES, LITERAL_EQUALS));

    // DOUBLE / FLOAT TYPES
    String FINITE = "FINITE";
    String INFINITE = "INFINITE";
    String NAN = "NAN";

    // OTHER
    String ASSIGN = "=";
    String COLON = ":";
    String THREE_DOTS = "...";
    String NOT = "NOT";
    String EMPTY_RECORD = "__EMPTY_RECORD__";
}

class Utils implements Globals {
    public static boolean escapeNext(StringBuilder sb) {
        if (sb.isEmpty()) return false;

        int last = sb.length() - 1;
        char taken = sb.charAt(last);

        sb.deleteCharAt(last);
        boolean willBe = (taken == '\\' && !escapeNext(sb));
        sb.append(taken);

        return willBe;
    }

    public static Integer arrayProduct(int[] array) throws ArithmeticException {
        int product = 1;

        for (int elm : array) {
            product = Math.multiplyExact(product, elm);
        }

        return product;
    }

    public static Integer compareObjectWithString(Object obj, String string) throws NumberFormatException {

        PrimitiveLike<?> prim = new PrimitiveLike<>(obj.getClass());
        if (!prim.isPrimitiveLike) throw new NumberFormatException("unknown parser");
        Object obj2 = prim.parse(string);

        if (obj instanceof Float && Float.isNaN((float) obj)) return null;
        if (obj instanceof Double && Double.isNaN((double) obj)) return null;
        if (obj2 instanceof Float && Float.isNaN((float) obj2)) return null;
        if (obj2 instanceof Double && Double.isNaN((double) obj2)) return null;

        if (obj instanceof Comparable) {

            @SuppressWarnings("unchecked")
            Comparable<Object> objCmp = (Comparable<Object>) obj;
            return objCmp.compareTo(obj2);

        } else throw new NumberFormatException("type not comparable");
    }

    public static boolean decimalIs(Object obj, String what) {
        if (obj instanceof Float) {

            float f = (float) obj;
            return switch (what) {
                case FINITE -> Float.isFinite(f);
                case INFINITE -> Float.isInfinite(f);
                case NAN -> Float.isNaN(f);
                default -> false;
            };

        } else if (obj instanceof Double) {

            double d = (double) obj;
            return switch (what) {
                case FINITE -> Double.isFinite(d);
                case INFINITE -> Double.isInfinite(d);
                case NAN -> Double.isNaN(d);
                default -> false;
            };
        }

        return false;
    }

    public static String forceUnicode(String base) {
        StringBuilder sb = new StringBuilder();

        int lngt = base.length();
        for (int i = 0; i < lngt; i++) {
            char c = base.charAt(i);

            if (Character.isHighSurrogate(c)) {
                if (i + 1 < lngt && Character.isLowSurrogate(base.charAt(i + 1))) {
                    int codePoint = Character.toCodePoint(c, base.charAt(i + 1));
                    sb.appendCodePoint(codePoint);
                    i++;

                } else sb.append('\uFFFD');

            } else sb.append(Character.isLowSurrogate(c) ? '\uFFFD' : c);
        }

        return sb.toString();
    }

    public static void recursiveAdjustDimension(Set<String> varNames, int[] dimArray, HashLabel hashLabel, int dim, int min, int max) {
        // sets given dimension in such way, that it becomes the last possible index
        int check = min + (max - min) / 2;

        dimArray[dim] = check + 1;
        boolean right = varNames.contains(hashLabel.withIndexes(dimArray));

        dimArray[dim] = check; // final set in this call
        boolean left = check == -1 || varNames.contains(hashLabel.withIndexes(dimArray));

        if (!left || right) { // not yet adjusted
            if (!left) recursiveAdjustDimension(varNames, dimArray, hashLabel, dim, min, check - 1); // <--- go left
            else recursiveAdjustDimension(varNames, dimArray, hashLabel, dim, check + 1, max); // go right --->
        }
    }

    public static byte[] megaConcat(ArrayList<byte[]> multiBuffer) throws ArithmeticException {
        long totalSize = multiBuffer
                .stream()
                .mapToLong(bytes -> bytes.length)
                .sum();

        if (totalSize != (long) (int) totalSize) {
            throw new ArithmeticException("Not enough memory to merge given arrays.");
        }

        byte[] buffer = new byte[(int) totalSize];

        int ptr = 0;
        for (byte[] bytes : multiBuffer) {
            System.arraycopy(bytes, 0, buffer, ptr, bytes.length);
            ptr += bytes.length;
        }

        return buffer;
    }

    public static int fastHash3B(String str) { // CRC32 with modifications
        CRC32 crc32 = new CRC32();
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        crc32.update(bytes, 0, bytes.length);
        return ((int) crc32.getValue()) & 0x00_FF_FF_FF;
    }
}

class DoubleHashMap<K, V> {
    private final Map<K, V> keyToValue;
    private final Map<V, K> valueToKey;

    public DoubleHashMap() {
        keyToValue = new LinkedHashMap<>();
        valueToKey = new LinkedHashMap<>();
    }

    private DoubleHashMap(DoubleHashMap<K, V> other) {
        keyToValue = Collections.unmodifiableMap(other.keyToValue);
        valueToKey = Collections.unmodifiableMap(other.valueToKey);
    }

    public int size() {
        return keyToValue.size();
    }

    public DoubleHashMap<K, V> unmodifiableView() {
        return new DoubleHashMap<>(this);
    }

    public void addPair(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("Null not supported.");

        removeByKey(key);
        removeByValue(value);

        keyToValue.put(key, value);
        valueToKey.put(value, key);
    }

    public boolean containsKey(K key) {
        return keyToValue.containsKey(key);
    }

    public boolean containsValue(V value) {
        return valueToKey.containsKey(value);
    }

    public V getValue(K key) {
        return keyToValue.get(key);
    }

    public K getKey(V value) {
        return valueToKey.get(value);
    }

    public boolean removeByKey(K key) {
        V value = keyToValue.remove(key);
        if (value == null) return false;

        valueToKey.remove(value);
        return true;
    }

    public boolean removeByValue(V value) {
        K key = valueToKey.remove(value);
        if (key == null) return false;

        keyToValue.remove(key);
        return true;
    }

    public Set<K> keySet() {
        return keyToValue.keySet();
    }

    public Set<V> valueSet() {
        return valueToKey.keySet();
    }
}

class Validator {
    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String ARRAY_INDEX = "(?:\\[[0-9]+])";
    private static final String PATH_SEGMENT = IDENTIFIER + ARRAY_INDEX + "?";

    private static final Pattern VAR_NAME_PATTERN =
            Pattern.compile("^" + PATH_SEGMENT + "(?:\\." + PATH_SEGMENT + ")*$");

    private static final Pattern STRUCT_NAME_PATTERN = Pattern.compile("^" + IDENTIFIER + "$");

    public static boolean validVarName(String varName) {
        if (varName == null || varName.isEmpty()) return false;
        return VAR_NAME_PATTERN.matcher(varName).matches();
    }

    public static boolean validRecordName(String recordName) {
        if (recordName == null || recordName.isEmpty()) return false;
        return STRUCT_NAME_PATTERN.matcher(recordName).matches();
    }
}

class Parsing {
    public static boolean parseBoolean(String s) throws NumberFormatException {
        if (s == null) throw new NumberFormatException("null");

        if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
        if (s.equalsIgnoreCase("false") || s.equals("0")) return false;

        throw new NumberFormatException("unknown boolean: " + s);
    }

    public static char parseCharacter(String s) throws NumberFormatException {
        if (s == null) throw new NumberFormatException("null");

        // 'A' or '\n' or '\u0081'
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            String inner = s.substring(1, s.length() - 1);
            String parsed = parseString("\"" + inner + "\"");

            if (parsed.length() == 1) {
                return parsed.charAt(0);
            }

            throw new NumberFormatException("not a character");
        }

        // %65 -> char code
        if (s.length() > 1 && s.charAt(0) == '%') {
            return (char) Integer.parseInt(s.substring(1));
        }

        // single char literal
        if (s.length() == 1) {
            return s.charAt(0);
        }

        throw new NumberFormatException("unknown char format: " + s);
    }

    public static String parseString(String s) throws NumberFormatException {
        if (s == null) throw new NumberFormatException("null");

        // "text\n" with escapes
        if (s.length() >= 2 && s.charAt(0) == '\"' && s.charAt(s.length() - 1) == '\"') {
            String inner = s.substring(1, s.length() - 1);

            int pos, lngt = inner.length();
            for (pos = 0; pos < lngt; pos++) {
                if (!Character.isWhitespace(inner.charAt(pos)))
                    break;
            }

            String whiteFront = inner.substring(0, pos);
            String blackBack = inner.substring(pos, lngt);

            try {
                Properties props = new Properties();
                props.load(new StringReader("k=" + blackBack + "\n"));
                return whiteFront + props.getProperty("k");

            } catch (Exception ex) {
                throw new NumberFormatException("properties class failed to parse: " + s);
            }
        }

        throw new NumberFormatException("unknown string format: " + s);
    }
}

class SizeReducer {
    public static byte sizeCompress(int size) {
        size = Math.max(1, size); // non-positives are illegal
        int baseExponent = Integer.SIZE
                - Integer.numberOfLeadingZeros(size)
                - 1; // 0-30, 31 impossible (illegal negatives)
        byte candidate = (byte) (baseExponent << 3);
        while (sizeDecompress(candidate) < size) candidate++;
        return candidate;
    }

    public static int sizeDecompress(byte compressed) {
        int baseExponent = (compressed & 0b11111000) >> 3;
        int precisePart = compressed & 0b00000111;
        int ret = (0b1 << baseExponent) + (int) ((precisePart * (((long) 0b1) << baseExponent)) >> 3);
        return ret > 0 ? ret : Integer.MAX_VALUE; // non-positive means overflow
    }
}

class Tokenizer implements Globals {

    public static String[] splitLineIntoTokens(String line) throws SyntaxException {
        ArrayList<String> tokenBuilder = new ArrayList<>();
        StringBuilder stringBuilder = null;

        char tokenizeMode = 0;
        int len = line.length();
        for (int i = 0; i < len; i++) {
            char ch = line.charAt(i);

            if (tokenizeMode == 0) {
                if (ch == '\'' || ch == '\"') {
                    tokenizeMode = ch;
                }

            } else {
                // stringBuilder is never null here
                if (ch == tokenizeMode && !Utils.escapeNext(stringBuilder)) {
                    tokenizeMode = 0;
                }
            }

            if (tokenizeMode == 0 && Character.isWhitespace(ch)) {
                if (stringBuilder != null) { // flush
                    tokenBuilder.add(stringBuilder.toString());
                    stringBuilder = null;
                }

            } else {
                if (stringBuilder == null) {
                    stringBuilder = new StringBuilder();
                }

                stringBuilder.append(ch);
            }
        }

        if (tokenizeMode != 0) throw new SyntaxException("Unclosed quote of type: " + tokenizeMode);
        if (stringBuilder != null) tokenBuilder.add(stringBuilder.toString());

        return tokenBuilder.toArray(new String[0]);
    }

    public static ArrayList<String[]> splitTokensIntoInstructions(String[] tokens)
            throws SyntaxException {

        if (tokens.length > 0 && !KEYWORDS.contains(tokens[0]))
            throw new SyntaxException("Token '" + tokens[0] + "' is not a valid keyword.");

        ArrayList<String[]> retList = new ArrayList<>();
        String matchStr =
                Arrays.stream(tokens)
                        .map(s -> KEYWORDS.contains(s) ? "X" : "x")
                        .collect(Collectors.joining());
        Matcher matcher = Pattern.compile("Xx*").matcher(matchStr);

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            String[] instruction = Arrays.copyOfRange(tokens, start, end);
            retList.add(instruction);
        }

        return retList;
    }

    public static String removeComments(String rawText, boolean isCheckRun) {

        // Block comments
        Pattern multiLinePattern = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
        Matcher multiLineMatcher = multiLinePattern.matcher(rawText);
        String text1 =
                multiLineMatcher.replaceAll(
                        match -> {
                            String comment = match.group();
                            return comment.replaceAll("[^\\n]", "");
                        });

        // Single line comments
        Pattern singleLinePattern = Pattern.compile("//.*$", Pattern.MULTILINE);
        Matcher singleLineMatcher = singleLinePattern.matcher(text1);
        String text2 = singleLineMatcher.replaceAll("");

        // Line conditional remove markers
        Pattern remMarkerPattern =
                Pattern.compile(isCheckRun ? "^\\s*_+" : "^\\s*_+.*", Pattern.MULTILINE);
        Matcher remMarkerMatcher = remMarkerPattern.matcher(text2);
        return remMarkerMatcher.replaceAll("");
    }

    public static VariableInfo[] parseArrayText(String varType, int arrSize, String defaultValue)
            throws SyntaxException {
        VariableInfo[] varInfos;

        // default value => string (insert)
        if (varType.equals(CHAR)
                && defaultValue != null
                && !defaultValue.isEmpty()
                && defaultValue.charAt(0) == '\"'
                && !defaultValue.contains("~")) {
            varInfos = parseStringInfo(defaultValue, arrSize);
        }

        // list separated with NULLs => fill using list
        else if (defaultValue != null) {
            varInfos = new VariableInfo[arrSize];
            LinkedList<String> tokenList = new LinkedList<>(Arrays.asList(defaultValue.split("~")));
            boolean threeDotFill = tokenList.getLast().equals(THREE_DOTS);

            if (threeDotFill) {
                tokenList.removeLast();
            }

            if (tokenList.size() > arrSize) {
                throw new SyntaxException(
                        "List of size "
                                + tokenList.size()
                                + " is too long to fit into array of size "
                                + arrSize
                                + ".");
            }

            int pos = 0;
            while (!tokenList.isEmpty()) { // take tokens and add them
                String token = tokenList.removeFirst();
                varInfos[pos++] = new VariableInfo(varType, token);
            }

            VariableInfo filler =
                    (threeDotFill && pos > 0) ? varInfos[pos - 1] : new VariableInfo(varType, null);
            Arrays.fill(varInfos, pos, arrSize, filler);
        }

        // null => fill with default value
        else {
            varInfos = new VariableInfo[arrSize];
            Arrays.fill(varInfos, new VariableInfo(varType, null));
        }

        return varInfos;
    }

    public static VariableInfo[] parseStringInfo(String quoteValue, int arrSize)
            throws SyntaxException {

        VariableInfo varInfo_NULL = new VariableInfo(CHAR, null); // NULL character
        String defaultString;

        try {
            defaultString = Parsing.parseString(quoteValue);
        } catch (NumberFormatException ex) {
            throw new SyntaxException("Parsing " + quoteValue + " as string failed.");
        }

        if (defaultString.length() > arrSize) {
            throw new SyntaxException("String " + quoteValue + " with length of " + defaultString.length()
                    + " is too long to fit into array of size " + arrSize + ".");
        }

        VariableInfo[] varInfos = new VariableInfo[arrSize];
        int strLen = defaultString.length();

        for (int i = 0; i < arrSize; i++) {
            if (i < strLen) {
                int ch_ind = defaultString.charAt(i);
                varInfos[i] = new VariableInfo(CHAR, String.format("'\\u%04X'", ch_ind));

            } else {
                varInfos[i] = varInfo_NULL;
            }
        }

        return varInfos;
    }
}

@SuppressWarnings("unchecked")
class PrimitiveLike<T> implements Globals {
    public final boolean isPrimitiveLike;
    public final Class<?> clazz;
    private final TypeHandler handler;

    private enum TypeHandler {
        LC_BYTE(Byte.class, 1, Byte::parseByte, (byte) 0, (b, v) -> b.put((byte) v), ByteBuffer::get),
        LC_SHORT(Short.class, 2, Short::parseShort, (short) 0, (b, v) -> b.putShort((short) v), ByteBuffer::getShort),
        LC_INT(Integer.class, 4, Integer::parseInt, 0, (b, v) -> b.putInt((int) v), ByteBuffer::getInt),
        LC_LONG(Long.class, 8, Long::parseLong, 0L, (b, v) -> b.putLong((long) v), ByteBuffer::getLong),
        LC_FLOAT(Float.class, 4, Float::parseFloat, 0.0f, (b, v) -> b.putFloat((float) v), ByteBuffer::getFloat),
        LC_DOUBLE(Double.class, 8, Double::parseDouble, 0.0d, (b, v) -> b.putDouble((double) v), ByteBuffer::getDouble),
        LC_CHAR(Character.class, 2, Parsing::parseCharacter, (char) 0, (b, v) -> b.putChar((char) v), ByteBuffer::getChar),
        LC_BOOLEAN(Boolean.class, 1, Parsing::parseBoolean, false, (b, v) -> b.put((byte) ((boolean) v ? 1 : 0)), b -> b.get() == 1);

        final Class<?> type;
        final byte len;
        final Function<String, Object> parser;
        final Object defValue;
        final BiConsumer<ByteBuffer, Object> serializer;
        final Function<ByteBuffer, Object> deserializer;

        TypeHandler(Class<?> type, int len, Function<String, Object> parser, Object defValue,
                    BiConsumer<ByteBuffer, Object> serializer, Function<ByteBuffer, Object> deserializer) {
            this.type = type;
            this.len = (byte) len;
            this.parser = parser;
            this.defValue = defValue;
            this.serializer = serializer;
            this.deserializer = deserializer;
        }

        static final Map<Class<?>, TypeHandler> BY_CLASS = new HashMap<>();
        static final Map<String, TypeHandler> BY_NAME = new HashMap<>();

        static {
            for (TypeHandler h : values()) BY_CLASS.put(h.type, h);
            BY_NAME.put(BYTE, LC_BYTE);
            BY_NAME.put(SHORT, LC_SHORT);
            BY_NAME.put(INT, LC_INT);
            BY_NAME.put(LONG, LC_LONG);
            BY_NAME.put(FLOAT, LC_FLOAT);
            BY_NAME.put(DOUBLE, LC_DOUBLE);
            BY_NAME.put(CHAR, LC_CHAR);
            BY_NAME.put(BOOLEAN, LC_BOOLEAN);
        }
    }

    // --- CONSTRUCTORS ---

    public PrimitiveLike(Class<T> clazz) {
        this.handler = TypeHandler.BY_CLASS.get(clazz);
        this.isPrimitiveLike = handler != null;
        this.clazz = isPrimitiveLike ? clazz : null;
    }

    public PrimitiveLike(String typeName) {
        this.handler = TypeHandler.BY_NAME.get(typeName);
        this.isPrimitiveLike = handler != null;
        this.clazz = isPrimitiveLike ? handler.type : null;
    }

    // --- PUBLIC API ---

    public T parse(String str) {
        return isPrimitiveLike ? (T) handler.parser.apply(str) : null;
    }

    public T defaultValue() {
        return isPrimitiveLike ? (T) handler.defValue : null;
    }

    public byte[] serialize(T value) {
        if (!isPrimitiveLike) return new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(handler.len).order(ByteOrder.BIG_ENDIAN);
        handler.serializer.accept(buffer, value);
        return buffer.array();
    }

    public byte[] serializeObject(Object obj) {
        return serialize((T) obj);
    }

    public T deserialize(byte[] bytes, int offset) {
        if (!isPrimitiveLike) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, handler.len).order(ByteOrder.BIG_ENDIAN);
        return (T) handler.deserializer.apply(buffer);
    }

    public byte byteLength() {
        return isPrimitiveLike ? handler.len : 0;
    }
}

class ArrayLabel {
    public final boolean isArray;
    public final String name;
    public final Integer index;

    public ArrayLabel(String varName) {
        if (validArrayFormat(varName)) {
            isArray = true;
            name = extractName(varName);
            index = extractIndex(varName);

        } else {
            isArray = false;
            name = varName;
            index = null;
        }
    }

    public String withIndex(int index) {
        return name + "[" + index + "]";
    }

    private boolean validArrayFormat(String varName) {
        return Validator.validVarName(varName) && varName.matches(".*\\[[0-9]+]$");
    }

    private String extractName(String arrName) {
        return arrName.replaceFirst("\\[[0-9]+]$", "");
    }

    private Integer extractIndex(String arrName) {
        Matcher matcher = Pattern.compile("\\[[0-9]+]$").matcher(arrName);

        if (matcher.find()) {
            String indPart = matcher.group();
            indPart = indPart.substring(1, indPart.length() - 1);

            try {
                return Integer.parseInt(indPart);

            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return null;
    }
}

class HashLabel {
    public final int dimension;
    public final String pattern;

    public HashLabel(String path) {
        String indRegex = "\\[[0-9]+]|\\[\\*]";
        Matcher matcher = Pattern.compile(indRegex).matcher(path);

        int dim = 0;
        while (matcher.find()) dim++;
        dimension = dim;
        pattern = path.replaceAll(indRegex, "[*]");
    }

    public String withIndexes(int[] indexes) {
        int i = 0;

        String retStr = pattern;
        while (i < dimension) {
            retStr = retStr.replaceFirst(Pattern.quote("*"), indexes[i] + "");
            i++;
        }

        return retStr;
    }

    public String withEmptyIndex() {
        return pattern.replaceAll(Pattern.quote("*"), "0");
    }
}

class GroupIterator {
    private final int[] iterationArray; // range to iterate through
    private final int[] validArray; // indicates whether iterator result is valid
    private final int[] iterator;

    private final boolean hasValidator;
    private boolean isValid = false;
    private boolean firstIterated = false; // for edge cases

    public GroupIterator(int[] iterationArray) {
        this(iterationArray, iterationArray, false);
    }

    public GroupIterator(int[] iterationArray, int[] validArray) {
        this(iterationArray, validArray, true);
    }

    private GroupIterator(int[] iterationArray, int[] validArray, boolean hasValidator) {
        if (iterationArray.length != validArray.length) {
            throw new IllegalArgumentException("Must be the same length.");
        }
        if (Arrays.stream(iterationArray).anyMatch(n -> n <= 0)) {
            throw new IllegalArgumentException("All dimensions must be positive.");
        }

        this.iterationArray = Arrays.copyOf(iterationArray, iterationArray.length);
        this.validArray = Arrays.copyOf(validArray, validArray.length);
        this.iterator = new int[iterationArray.length];

        this.hasValidator = hasValidator;
    }

    public int[] iterate() {
        if (!firstIterated) {
            Arrays.fill(iterator, 0);
            firstIterated = true;

        } else {
            if (iterator.length == 0)
                return null;

            for (int i = 0; true; i++) {
                iterator[i]++;
                if (iterator[i] >= iterationArray[i]) {
                    iterator[i] = 0;
                    if (i == iterator.length - 1) {
                        isValid = false;
                        return null; // iteration end
                    }
                    continue;
                }
                break;
            }
        }

        isValid = true;
        if (hasValidator) {
            for (int i = 0; i < iterator.length; i++) {
                if (iterator[i] >= validArray[i]) {
                    isValid = false;
                    break;
                }
            }
        }

        return iterator;
    }

    public boolean isIncluded() {
        return isValid;
    }
}

class Ensuration implements Globals {
    private final List<Condition> conditions = new ArrayList<>();

    public void insertCondition(String fieldName, String[] tokens) throws SyntaxException {
        conditions.add(Condition.createCondition(fieldName, tokens));
    }

    public void inheritConditions(Ensuration other) {
        for (Condition cond : other.conditions) {
            Condition newCond = cond.copyWithPrefix("");
            conditions.add(newCond);
        }
    }

    public void nestConditions(Ensuration other, String path) {
        for (Condition cond : other.conditions) {
            Condition newCond = cond.copyWithPrefix(path + ".");
            conditions.add(newCond);
        }
    }

    public void ensureAndFix(Record record) {
        for (Condition cond : conditions) {
            cond.ensureAndFix(record);
        }
    }

    private static class Condition {
        private final String fieldName;
        private final ArrayList<ArrayList<String>> orConditions = new ArrayList<>();

        private boolean varCondition = false;
        private boolean strCondition = false;

        private Condition(String fieldName) {
            this.fieldName = fieldName;
        }

        private void addOr() {
            orConditions.add(new ArrayList<>());
        }

        private void addAnd() {
            addAnd("");
        }

        private void addAnd(String line) {
            if (orConditions.isEmpty()) addOr();
            orConditions.getLast().add(line);
        }

        private void insertToken(String token) throws SyntaxException {
            if (orConditions.isEmpty()) addOr();
            if (orConditions.getLast().isEmpty()) addAnd();

            orConditions.getLast().add(orConditions.getLast().removeLast() + token + "~");
            varCondition |= VAR_OPERATORS.contains(token);
            strCondition |= STR_OPERATORS.contains(token);

            if (varCondition && strCondition) {
                throw new SyntaxException(
                        "Cannot have both variable and string operator in one " + ENSURE + " command.");
            }
        }

        public static Condition createCondition(String varName, String[] tokens)
                throws SyntaxException {
            Condition build = new Condition(varName);

            for (String token : tokens) {
                switch (token) {
                    case OR -> build.addOr();
                    case AND -> build.addAnd();
                    default -> build.insertToken(token);
                }
            }

            return build;
        }

        public Condition copyWithPrefix(String prefix) {
            Condition build = new Condition(prefix + fieldName);

            for (ArrayList<String> cnds : orConditions) {
                build.addOr();
                for (String cnd : cnds) {
                    build.addAnd(cnd);
                }
            }

            return build;
        }

        public void ensureAndFix(Record record) {
            ensureAndFix(record, fieldName);
        }

        private void ensureAndFix(Record record, String fieldName) {
            ArrayLabel fieldLabel = new ArrayLabel(fieldName);

            int i, lngt_i = orConditions.size();
            for (i = 0; i < lngt_i; i++) {
                ArrayList<String> andConditions = orConditions.get(i);
                boolean fixOnFalse = i == lngt_i - 1; // last or chance

                boolean allOk = true;

                int j, lngt_j = andConditions.size();
                for (j = 0; j < lngt_j; j++) {
                    String conditionLine = andConditions.get(j);
                    Boolean wasGood = ensureAndFixField(record, fieldName, conditionLine, fixOnFalse);

                    if (Boolean.TRUE.equals(wasGood)) {
                        continue; // condition ok, next and

                    } else if (Boolean.FALSE.equals(wasGood)) {
                        allOk = false;
                        break; // condition wrong, try next or

                    } else { // array fallback
                        if (!fieldLabel.isArray) {
                            int k = 0;
                            String arrFieldName;

                            do {
                                arrFieldName = fieldLabel.withIndex(k);
                                if (record.getValue(arrFieldName) == null) arrFieldName = null;

                                if (arrFieldName != null) {
                                    ensureAndFix(record, arrFieldName);
                                }

                                k++;
                            } while (arrFieldName != null);

                        } else throw new OperationException("Couldn't find array element '" +
                                record.RECORD_TYPE + "::" + fieldLabel + "' during " + ENSURE + " execution.");

                        return;
                    }
                }

                if (allOk) break; // one and passed
            }
        }

        private Boolean ensureAndFixField(
                Record record, String fieldName, String line, boolean fixOnFalse) {

            try {
                boolean negation = line.startsWith(NOT + "~");
                String instr = negation ? line.substring(NOT.length() + 1) : line;
                String[] args = instr.split("~");

                if (args.length < 2) {
                    throw new OperationException(
                            "Unrecognizable " + ENSURE + " pattern '" + String.join(" ", line.split("~")) + "'.");
                }

                String operator = args[0];
                String argument = args[1];

                boolean isGood;
                if (VAR_OPERATORS.contains(operator)) {
                    if (record.getValue(fieldName) != null) { // variable
                        isGood = negation ^ isValidVariable(record, fieldName, operator, argument);
                        if (!isGood && fixOnFalse) {

                            record.resetValue(fieldName);
                            record.repairedFlag = true;
                        }

                    } else return null; // return info to include array instead of variable

                } else if (STR_OPERATORS.contains(operator)) { // string
                    isGood = negation ^ isValidString(record, fieldName, operator, argument);
                    if (!isGood && fixOnFalse) {

                        record.resetArray(fieldName);
                        record.repairedFlag = true;
                    }

                } else throw new OperationException("Unknown " + ENSURE + " operator '" + operator + "'.");

                return isGood;

            } catch (NumberFormatException ex) {
                throw new OperationException(
                        "Parsing error in " + ENSURE + " command at condition: '" + fieldName + " " + line + "'");

            } catch (PatternSyntaxException ex) {
                throw new OperationException(
                        "REGEX syntax error at index = " + ex.getIndex() + " in " + ENSURE + " command: \"" + ex.getPattern() + "\"\n" +
                                ex.getMessage());
            }
        }

        private static boolean isValidVariable(
                Record record, String varName, String operator, String argument)
                throws NumberFormatException {

            Object obj = record.getValue(varName);

            switch (operator) {
                case LARGER, LARGER_OR_EQ, SMALLER, SMALLER_OR_EQ, EQUALS, NOT_EQUALS -> {

                    Integer cmp = Utils.compareObjectWithString(obj, argument);
                    if (cmp == null) { // NaN detected
                        // when NaN is detected, comparison result depends only on operator
                        return operator.equals(NOT_EQUALS);
                    }

                    return switch (operator) {
                        case LARGER -> cmp > 0;
                        case LARGER_OR_EQ -> cmp >= 0;
                        case SMALLER -> cmp < 0;
                        case SMALLER_OR_EQ -> cmp <= 0;
                        case EQUALS -> cmp == 0;
                        case NOT_EQUALS -> cmp != 0;
                        default -> false;
                    };
                }

                case IS -> {
                    return switch (operator) {
                        case IS -> Utils.decimalIs(obj, argument);
                        default -> false;
                    };
                }

                default -> throw new OperationException(
                        "Unknown " + ENSURE + " syntax operator: " + operator);
            }
        }

        private static boolean isValidString(
                Record record, String fieldName, String operator, String argument)
                throws NumberFormatException, PatternSyntaxException {

            String str = record.getString(fieldName); // never returns null, only empty string

            switch (operator) {
                case MATCHES -> {
                    String regex = Parsing.parseString(argument);
                    Matcher matcher = Pattern.compile(regex).matcher(str);
                    return matcher.find();
                }

                case LITERAL_EQUALS -> {
                    String literal = Parsing.parseString(argument);
                    return str.equals(literal);
                }

                default -> throw new OperationException(
                        "Unknown string " + ENSURE + " syntax operator: " + operator);
            }
        }
    }
}

class VariableInfo implements Globals {
    public final String typeName;
    public final Class<?> clazz;
    public final Function<String, Object> parser;
    public final Object defaultValue;

    public VariableInfo(String typeName, String defaultAsString) throws SyntaxException {
        PrimitiveLike<?> prim = new PrimitiveLike<>(typeName);
        if (!prim.isPrimitiveLike) throw new SyntaxException("Wrong type name: '" + typeName + "'");

        try {
            this.typeName = typeName;
            this.clazz = prim.clazz;
            this.parser = prim::parse;
            this.defaultValue =
                    defaultAsString != null ? parser.apply(defaultAsString) : prim.defaultValue();

        } catch (NumberFormatException ex) {
            throw new SyntaxException(
                    "Couldn't parse value '" + defaultAsString + "' as type '" + typeName + "'.");
        }
    }

    public VariableInfo reassignDefault(String newDefault) throws SyntaxException {
        return new VariableInfo(typeName, newDefault);
    }
}

class RecordInfo implements Globals {
    public final String RECORD_TYPE;
    public final boolean IS_RECORD;

    private final Ensuration localEnsuration = new Ensuration();

    private final Map<String, RecordInfo> records; // all delcared records
    private final Map<String, VariableInfo> variableInfos = new LinkedHashMap<>();
    private final DoubleHashMap<String, Integer> fieldToHash = new DoubleHashMap<>();

    public RecordInfo(String recordType, boolean isRecord, Map<String, RecordInfo> records) throws SyntaxException {
        if (!Validator.validRecordName(recordType))
            throw new SyntaxException("Wrong record name: '" + recordType + "'");

        this.RECORD_TYPE = recordType;
        this.IS_RECORD = isRecord;
        this.records = records;
    }

    public void applyCommand(String[] args) throws SyntaxException {
        String cmd = args[0];
        switch (cmd) {

            /// 0:LET 1:[varType] 2:[varName] ?? 3:EQUALS 4...:[defaultValue]
            case LET -> applyLet(args);

            /// 0:SET 1:[varName] ?? 2:EQUALS 3...:[defaultValue]
            case SET -> applySet(args);

            /// 0:NEST 1:[nestType] 2:[nestStructRoute]
            case NEST -> applyNest(args);

            /// 0:BASE 1...:[baseType]
            case BASE -> applyBase(args);

            /// 0:ENSURE 1:[fieldName] 2...:conditions
            case ENSURE -> applyEnsure(args);

            /// WRONG COMMAND
            default -> throw new SyntaxException("Command " + cmd + " is not implemented.");
        }
    }

    private void applyLet(String[] args) throws SyntaxException {
        boolean isShortLet = args.length == 3;
        boolean isLongLet = args.length >= 5 && args[3].equals(ASSIGN);

        if (isShortLet || isLongLet) {
            String varType = args[1];
            String varName = args[2];
            String defaultValue = isLongLet ?
                    String.join("~", Arrays.copyOfRange(args, 4, args.length)) : null;

            if (!Validator.validVarName(varName))
                throw new SyntaxException("Wrong variable name: '" + varName + "'");

            if (varName.contains("]."))
                throw new SyntaxException(
                        "Cannot explicitly declare nested variable " + RECORD_TYPE + "::" + varName + ".");

            registerVariable(varType, varName, defaultValue);

        } else throw new SyntaxException("Wrong pattern in " + LET + " command.");
    }

    private void applySet(String[] args) throws SyntaxException {
        boolean isShortSet = args.length == 2;
        boolean isLongSet = args.length >= 4 && args[2].equals(ASSIGN);

        if (isShortSet || isLongSet) {
            String varName = args[1];
            String defaultValue =
                    isLongSet ? String.join("~", Arrays.copyOfRange(args, 3, args.length)) : null;

            if (!Validator.validVarName(varName))
                throw new SyntaxException("Wrong variable name: '" + varName + "'");

            overwriteVariable(varName, defaultValue);

        } else throw new SyntaxException("Wrong pattern in " + SET + " command.");
    }

    private void applyNest(String[] args) throws SyntaxException {
        boolean isOkNest = args.length == 3;

        if (isOkNest) {
            String nestType = args[1];
            String nestName = args[2];

            if (!Validator.validRecordName(nestType))
                throw new SyntaxException("Wrong record name: '" + nestType + "'.");

            if (!Validator.validVarName(nestName))
                throw new SyntaxException("Wrong nest name: '" + nestName + "'.");

            if (nestName.contains("]."))
                throw new SyntaxException("Cannot explicitly declare nested record " + RECORD_TYPE + "::" + nestName + ".");

            if (RECORD_TYPE.equals(nestType))
                throw new SyntaxException("Cannot nest record '" + RECORD_TYPE + "' inside itself.");

            if (!records.containsKey(nestType))
                throw new SyntaxException("Record '" + nestType + "' cannot be found. It should be declared above record '"
                        + RECORD_TYPE + "' to be nested inside.");

            RecordInfo nestInfo = records.get(nestType);
            Set<String> nestVarNames = nestInfo.getLinkedVariableSet();
            ArrayLabel arrLabel = new ArrayLabel(nestName);

            if (!arrLabel.isArray) { // interpret literally
                for (String nestVarName : nestVarNames) {
                    VariableInfo nestVarInfo = nestInfo.getVariableInfo(nestVarName);

                    String newPath = nestName + "." + nestVarName;
                    addVariable(newPath, nestVarInfo);
                }

                localEnsuration.nestConditions(nestInfo.localEnsuration, nestName);

            } else { // make array of nested
                int arrSize = arrLabel.index;
                for (int i = 0; i < arrSize; i++) {
                    String nestName2 = arrLabel.withIndex(i);

                    for (String nestVarName : nestVarNames) {
                        VariableInfo nestVarInfo = nestInfo.getVariableInfo(nestVarName);

                        String newPath = nestName2 + "." + nestVarName;
                        addVariable(newPath, nestVarInfo);
                    }

                    localEnsuration.nestConditions(nestInfo.localEnsuration, nestName2);
                }
            }

        } else throw new SyntaxException("Wrong pattern in " + NEST + " command.");
    }

    private void applyBase(String[] args) throws SyntaxException {
        for (int i = 1; i < args.length; i++) {
            String baseType = args[i];

            if (!Validator.validRecordName(baseType))
                throw new SyntaxException("Wrong record name: '" + baseType + "'.");

            if (RECORD_TYPE.equals(baseType))
                throw new SyntaxException("Cannot inherit record '" + RECORD_TYPE + "' from itself.");

            if (!records.containsKey(baseType))
                throw new SyntaxException("Record '" + baseType + "' cannot be found. It should be declared above record '"
                        + RECORD_TYPE + "' to be inherited.");

            RecordInfo baseInfo = records.get(baseType);
            Set<String> baseVarNames = baseInfo.getLinkedVariableSet();

            for (String baseVarName : baseVarNames) {
                VariableInfo nestVarInfo = baseInfo.getVariableInfo(baseVarName);
                addVariable(baseVarName, nestVarInfo);
            }

            localEnsuration.inheritConditions(baseInfo.localEnsuration);
        }
    }

    private void applyEnsure(String[] args) throws SyntaxException {
        boolean isOkEnsure = args.length >= 3;

        if (isOkEnsure) {
            String fieldName = args[1];

            if (!Validator.validVarName(fieldName))
                throw new SyntaxException("Wrong field name: '" + fieldName + "'");

            localEnsuration.insertCondition(fieldName, Arrays.copyOfRange(args, 2, args.length));

        } else throw new SyntaxException("Wrong pattern in " + ENSURE + " command.");
    }

    private void registerVariable(String varType, String varName, String defaultValue) throws SyntaxException {
        ArrayLabel arrLabel = new ArrayLabel(varName);

        if (arrLabel.isArray) { // array name: my_array[8]
            int arrSize = arrLabel.index;
            VariableInfo[] varInfos = Tokenizer.parseArrayText(varType, arrSize, defaultValue);

            for (int i = 0; i < arrSize; i++) {
                String name = arrLabel.withIndex(i);
                addVariable(name, varInfos[i]);
            }

        } else { // single name: my_variable
            VariableInfo varInfo = new VariableInfo(varType, defaultValue);
            addVariable(varName, varInfo);
        }
    }

    private void overwriteVariable(String varName, String defaultValue) throws SyntaxException {
        ArrayLabel arrLabel = new ArrayLabel(varName);

        if (variableInfos.containsKey(varName)) { // referring to single variable

            VariableInfo oldInfo = variableInfos.get(varName);
            VariableInfo newInfo = oldInfo.reassignDefault(defaultValue);
            variableInfos.put(varName, newInfo); // replace info with reassigned object

        } else if (!arrLabel.isArray && variableInfos.containsKey(arrLabel.withIndex(0))) { // referring to whole array
            int arrSize = getArrayLength(arrLabel.name);

            String varType = variableInfos.get(arrLabel.withIndex(0)).typeName; // reading TYPE
            VariableInfo[] varInfos = Tokenizer.parseArrayText(varType, arrSize, defaultValue);

            for (int i = 0; i < arrSize; i++) {
                String name = arrLabel.withIndex(i);
                variableInfos.put(name, varInfos[i]);
            }

        } else throw new SyntaxException( // no idea what user is referring to
                "Cannot overwrite variable or array called '" + RECORD_TYPE + "::" + arrLabel.name + "'.");
    }

    private void addVariable(String varName, VariableInfo varInfo) throws SyntaxException {
        ArrayLabel checkLabel = new ArrayLabel(varName);
        String checkName = checkLabel.isArray ? checkLabel.name : checkLabel.withIndex(0);

        if (variableInfos.containsKey(varName)) {
            throw new SyntaxException("Redefinition of variable '" + RECORD_TYPE + "::" + varName + "'.");
        }

        if (variableInfos.containsKey(checkName)) {
            throw new SyntaxException("Cannot declare variable '" + RECORD_TYPE + "::" + varName + "' when '" +
                    RECORD_TYPE + "::" + checkName + "' is already defined.");
        }

        // add to hash table if not exists
        HashLabel hashLabel = new HashLabel(varName);
        if (!fieldToHash.containsKey(hashLabel.pattern)) {
            int hash = Utils.fastHash3B(hashLabel.pattern);

            if (fieldToHash.containsValue(hash))
                throw new SyntaxException("Hash collision between fields '" + RECORD_TYPE + "::" + fieldToHash.getKey(hash) +
                        "' and '" + RECORD_TYPE + "::" + hashLabel.pattern + "'.");

            fieldToHash.addPair(hashLabel.pattern, hash);
        }

        // add to variable infos
        variableInfos.put(varName, varInfo);
    }

    // constructing data structs
    public Map<String, Object> getLinkedVariableMap() {
        Map<String, Object> result = new LinkedHashMap<>();

        for (var kvp : variableInfos.entrySet()) {
            String key = kvp.getKey();
            VariableInfo varInfo = kvp.getValue();
            result.put(key, varInfo.defaultValue);
        }
        return result;
    }

    public Set<String> getLinkedVariableSet() {
        return Collections.unmodifiableSet(variableInfos.keySet());
    }

    public DoubleHashMap<String, Integer> getHashingMapView() {
        return fieldToHash.unmodifiableView();
    }

    // checking
    public VariableInfo getVariableInfo(String varName) {
        return variableInfos.get(varName);
    }

    public boolean goodPrimitiveAt(String varName, Object obj) {
        VariableInfo varInfo = getVariableInfo(varName);
        if (varInfo != null) {
            Class<?> cl1 = obj.getClass();
            Class<?> cl2 = varInfo.clazz;
            return cl1.equals(cl2);
        }
        return false;
    }

    public int[] getGroupSizes(HashLabel hashLabel) {
        int[] tryArray = new int[hashLabel.dimension];
        Arrays.fill(tryArray, 0);

        for (int i = 0; i < tryArray.length; i++) {
            Utils.recursiveAdjustDimension(variableInfos.keySet(), tryArray, hashLabel, i, -1, Integer.MAX_VALUE - 1);
        }

        for (int i = 0; i < tryArray.length; i++) {
            tryArray[i]++;
        }

        return tryArray;
    }

    public int getArrayLength(String fieldName) {
        HashLabel hashLabel = new HashLabel(fieldName + "[*]");
        int[] groupSizes = getGroupSizes(hashLabel);

        return groupSizes[groupSizes.length - 1];
    }

    // tools
    public void checkAndFixRecord(Record record) {
        localEnsuration.ensureAndFix(record);
    }
}

public class SerialCompiler implements Globals {
    private final Compressor compressor;
    private final Map<String, RecordInfo> recordInfos = new HashMap<>();
    private final Map<Integer, String> recordHashReverter = new HashMap<>();
    private final boolean isPreChecking;

    private static class CompilationData {
        public final Map<String, String> defines = new HashMap<>();
        public final Map<String, String> defs = new HashMap<>();
        public RecordInfo record = null;
        public int line = 0;
    }

    private CompilationData compilation = new CompilationData();

    public SerialCompiler(String code) throws SyntaxException {
        this(
                code,
                new Compressor() {
                    @Override
                    public byte[] compress(byte[] data) {
                        return data;
                    }

                    @Override
                    public byte[] decompress(byte[] data) {
                        return data;
                    }
                });
    }

    public SerialCompiler(String code, Compressor compressor) throws SyntaxException {
        this(code, compressor, false);
    }

    private SerialCompiler(String code, Compressor compressor, boolean isPreChecking)
            throws SyntaxException {
        if (code == null || compressor == null) throw new NullPointerException();

        this.isPreChecking = isPreChecking;
        this.compressor = compressor;

        // preChecking -> ensuring that compilation with "__deprecation comments" is possible
        if (!isPreChecking) {
            new SerialCompiler(code, compressor, true);
        }

        // compilation... or throw SyntaxException
        try {
            compile(code);

        } catch (SyntaxException ex) {
            ex.setLine(compilation.line);
            throw ex;
        }

        // creating all record test-table
        ArrayList<Record> allRecords = new ArrayList<>();
        for (String recName : recordInfos.keySet()) {
            allRecords.add(makeRecord(recName));
        }

        // checking if default records pass ensurations
        for (Record record : allRecords) {
            Record copy = record.clone();

            boolean wasRepaired;
            try {
                wasRepaired = record.checkAndRepair();

            } catch (OperationException ex) { // ensuration error
                throw new SyntaxException(ENSURE + " error encountered while checking default record of type '"
                        + record.RECORD_TYPE + "':\n" + ex.getMessage());
            }

            if (wasRepaired) { // didn't pass ensurations
                throw new SyntaxException("Default record of type '" + record.RECORD_TYPE + "' doesn't meet given "
                        + ENSURE + " requirements.");
            }
        }

        // checking round-trip record serializations
        for (Record record : allRecords) {
            try {
                byte[] serialized = record.serialize();
                Record deserialized = makeRecord(serialized);

                if (!record.equals(deserialized))
                    throw new OperationException("Serialization failed!");

            } catch (Exception ex) { // any exception
                throw new SyntaxException(
                        "Problems encountered during round-trip serialization of record: '" + record.RECORD_TYPE + "'");
            }
        }
    }

    // record factory
    public Record makeRecord(String recordType) {
        if (recordType == null) throw new NullPointerException();

        return new Record(this, recordType);
    }

    public Record makeRecord(byte[] bytes) { // autoFix (default)
        return makeRecord(bytes, true);
    }

    public Record makeRecord(byte[] bytes, boolean autoFix) {
        if (bytes == null) throw new NullPointerException();

        Serializer serializer = new Serializer(this);
        Record deserialized = serializer.deserialize(bytes);

        if (autoFix) deserialized.checkAndRepair();
        return deserialized;
    }

    // serialization medium
    private byte[] compressNullSafe(byte[] bytes) {
        byte[] compressed = compressor.compress(bytes);
        return compressed != null ? compressed : new byte[0];
    }

    private byte[] decompressNullSafe(byte[] bytes) {
        byte[] decompressed = compressor.decompress(bytes);
        return decompressed != null ? decompressed : new byte[0];
    }

    private RecordInfo recordInfoFromHash(int recordHash) {
        String recordType = recordHashReverter.get(recordHash);
        return recordType != null ? recordInfos.get(recordType) : null;
    }

    // compilation
    private void compile(String serialCode) throws SyntaxException {
        serialCode = Tokenizer.removeComments(serialCode, isPreChecking);
        String[] codeLines = serialCode.split("\\R");

        applyCommand(new String[]{STRUCT, EMPTY_RECORD}); // empty record built-in declaration

        for (String codeLine : codeLines) {
            compilation.line++;
            String[] tokens = Tokenizer.splitLineIntoTokens(codeLine);
            ArrayList<String[]> instructions = Tokenizer.splitTokensIntoInstructions(tokens);

            for (String[] args : instructions) {
                if (Arrays.stream(args).anyMatch(arg -> arg.contains("~"))) {
                    throw new SyntaxException("Commands cannot contain '~' character. If inside string or character, use '\\u007E'.");
                }

                // clear local DEF-ines
                if (RECORD_KEYWORDS.contains(args[0])) compilation.defs.clear();

                // apply defines to command
                if (!DEFINE_KEYWORDS.contains(args[0]))
                    args =
                            Arrays.stream(args)
                                    .map(arg -> compilation.defs.getOrDefault(arg, compilation.defines.getOrDefault(arg, arg)))
                                    .toArray(String[]::new);

                applyCommand(args);
            }
        }

        // free compile data
        compilation = null;
    }

    private void applyCommand(String[] args) throws SyntaxException {
        String cmd = args[0];
        switch (cmd) {

            /// 0:RECORD/STRUCT 1:[recordType] ?? 2:COLON 3...:[baseType]
            case RECORD, STRUCT -> applyRecord(args);

            /// 0:DEFINE/DEF 1:[token] 2:[definition]
            case DEFINE, DEF -> applyDefDefine(args);

            /// inner record command
            default -> applyOther(args);
        }
    }

    private void applyRecord(String[] args) throws SyntaxException {
        String cmd = args[0];

        boolean isBaseStruct = args.length == 2;
        boolean isInheritStruct = args.length >= 4 && args[2].equals(COLON);

        if (isBaseStruct || isInheritStruct) {
            String recordType = args[1];
            if (!recordInfos.containsKey(recordType)) { // add and focus struct
                compilation.record = new RecordInfo(recordType, cmd.equals(RECORD), Collections.unmodifiableMap(recordInfos));

                // create record and add hash
                int recHash = Utils.fastHash3B(recordType);

                String hashedName = recordHashReverter.get(recHash);
                if (hashedName != null)
                    throw new SyntaxException("Hash collision involving record names '" + recordType + "' and '" + hashedName + "'.");

                recordHashReverter.put(recHash, recordType);
                recordInfos.put(recordType, compilation.record);

                // add base records
                for (int i = 3; i < args.length; i++) {
                    compilation.record.applyCommand(new String[]{BASE, args[i]});
                }

            } else throw new SyntaxException("Redefinition of record '" + recordType + "'.");

        } else throw new SyntaxException("Wrong pattern in " + RECORD + " command.");
    }

    private void applyDefDefine(String[] args) throws SyntaxException {
        String cmd = args[0];

        boolean isShortDefine = args.length == 3;
        boolean isLongDefine = args.length == 4 && args[2].equals(ASSIGN);

        if (isShortDefine || isLongDefine) {
            String token = args[1];
            String definition = args[isShortDefine ? 2 : 3];
            (cmd.equals(DEFINE) ? compilation.defines : compilation.defs).put(token, definition);

        } else throw new SyntaxException("Wrong pattern in " + cmd + " command.");
    }

    private void applyOther(String[] args) throws SyntaxException {
        String cmd = args[0];

        if (compilation.record == null || compilation.record.RECORD_TYPE.equals(EMPTY_RECORD))
            throw new SyntaxException("Command " + cmd + " must be used on a record.");

        compilation.record.applyCommand(args);
    }

    public static class Record {
        public final String RECORD_TYPE;

        private final SerialCompiler compiler;
        private final RecordInfo metadata;
        private final Map<String, Object> variables; // keys should not be changed!

        protected boolean repairedFlag = false;

        protected Record(SerialCompiler compiler, String recordType) {
            if (recordType == null) throw new NullPointerException();

            if (!compiler.recordInfos.containsKey(recordType))
                throw new OperationException("Record type '" + recordType + "' couldn't be found.");

            this.RECORD_TYPE = recordType;

            this.compiler = compiler;
            this.metadata = compiler.recordInfos.get(recordType);
            this.variables = metadata.getLinkedVariableMap();
        }

        // variables

        public Record setValue(String path, Object value) {
            if (path == null || value == null) throw new NullPointerException();

            if (!metadata.goodPrimitiveAt(path, value))
                throw new OperationException("Cannot write value of type '" + value.getClass() + "' into field '" +
                        RECORD_TYPE + "::" + path + "'.");

            variables.put(path, value);
            return this;
        }

        public Object getValue(String path) {
            if (path == null) throw new NullPointerException();
            return variables.get(path);
        }

        public <T> T getValue(String path, Class<T> clazz) {
            if (path == null || clazz == null) throw new NullPointerException();

            if (clazz.isPrimitive())
                throw new OperationException("Cannot use primitive types as clazz argument.");

            Object obj = getValue(path);
            if (obj == null) return null;
            if (!obj.getClass().equals(clazz)) return null;

            @SuppressWarnings("unchecked")
            T val = (T) obj;
            return val;
        }

        // arrays

        public Record setArray(String path, Object[] array) {
            if (path == null || array == null) throw new NullPointerException();

            if (array.length > 0) {

                if (metadata.getArrayLength(path) != array.length)
                    throw new OperationException("Provided array has a wrong size for field " + RECORD_TYPE + "::" + path + ".");

                for (int i = 0; i < array.length; i++) {
                    setValue(path + "[" + i + "]", array[i]);
                }
            }

            return this;
        }

        public <T> T[] getArray(String path, Class<T> clazz) {
            if (path == null || clazz == null) throw new NullPointerException();

            if (clazz.isPrimitive())
                throw new OperationException("Cannot use primitive types as clazz argument.");

            int size = metadata.getArrayLength(path);
            ArrayList<T> arrList = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                T elm = getValue(path + "[" + i + "]", clazz);
                if (elm == null) break;

                arrList.add(elm);
            }

            @SuppressWarnings("unchecked")
            T[] array = (T[]) Array.newInstance(clazz, arrList.size());
            return arrList.toArray(array);
        }

        // strings

        public Record setString(String path, String str) {
            if (path == null || str == null) throw new NullPointerException();

            int strLen = str.length();
            int bufferSize = metadata.getArrayLength(path);

            if (str.length() > bufferSize) {
                throw new OperationException("String is too long to fit into field " + RECORD_TYPE + "::" + path + ".");
            }

            Object[] objArray = new Object[bufferSize];
            for (int i = 0; i < objArray.length; i++) {
                objArray[i] = i < strLen ? str.charAt(i) : '\u0000';
            }

            setArray(path, objArray);
            return this;
        }

        public String getString(String path) {
            return getString(path, true); // Force Unicode (default)
        }

        public String getString(String path, boolean forceUnicode) {
            if (path == null) throw new NullPointerException();

            Character[] characterArray = getArray(path, Character.class);
            char[] charArray = new char[characterArray.length];

            for (int i = 0; i < characterArray.length; i++) {
                charArray[i] = characterArray[i];
            }

            String base = new String(charArray);
            if (forceUnicode) base = Utils.forceUnicode(base);
            return base.replaceAll("\u0000*$", "");
        }

        // records & cast

        public Record setRecord(String path, Record record) {
            if (path == null || record == null) throw new NullPointerException();

            for (String rawKey : record.variables.keySet()) {
                String fullKey = (path.isEmpty() ? "" : (path + ".")) + rawKey;
                Object obj = record.variables.get(rawKey);

                if (metadata.goodPrimitiveAt(fullKey, obj)) variables.put(fullKey, obj);
            }

            return this;
        }

        public Record getRecord(String path, String recordType) {
            if (path == null || recordType == null) throw new NullPointerException();

            Record record = compiler.makeRecord(recordType);
            for (String rawKey : record.variables.keySet()) {
                String fullKey = (path.isEmpty() ? "" : (path + ".")) + rawKey;

                if (variables.containsKey(fullKey)) {
                    Object obj = variables.get(fullKey);

                    if (record.metadata.goodPrimitiveAt(rawKey, obj)) {
                        record.variables.put(rawKey, obj);
                    }
                }
            }

            return record;
        }

        public Record castTo(String recordType) {
            if (recordType == null) throw new NullPointerException();
            return this.getRecord("", recordType);
        }

        public Record clone() {
            return castTo(RECORD_TYPE);
        }

        // resetting

        public Record resetValue(String path) {
            if (path == null) throw new NullPointerException();

            if (variables.containsKey(path)) {
                VariableInfo varInfo = metadata.getVariableInfo(path);
                variables.put(path, varInfo.defaultValue);
            }

            return this;
        }

        public Record resetArray(String path) {
            if (path == null) throw new NullPointerException();

            int size = metadata.getArrayLength(path);
            for (int i = 0; i < size; i++) {
                resetValue(path + "[" + i + "]");
            }

            return this;
        }

        public Record resetTree(String path) {
            if (path == null) throw new NullPointerException();

            String start1 = path + "["; // array or record array
            String start2 = path + "."; // record

            resetValue(path); // variable if present

            for (String varName : variables.keySet()) {
                if (varName.startsWith(start1) || varName.startsWith(start2)) {
                    resetValue(varName);
                }
            }

            return this;
        }

        // other

        public byte[] serialize() {
            Serializer serializer = new Serializer(this.getCompiler(), metadata);
            return serializer.serialize(this);
        }

        public boolean checkAndRepair() {
            repairedFlag = false; // clean flag
            metadata.checkAndFixRecord(this);
            return repairedFlag;
        }

        public SerialCompiler getCompiler() {
            return compiler;
        }


        public LinkedHashMap<String, Class<?>> getOrderedVariables() {
            LinkedHashMap<String, Class<?>> map = new LinkedHashMap<>();

            for (String varName : variables.keySet()) {
                VariableInfo varInfo = metadata.getVariableInfo(varName);
                map.put(varName, varInfo.clazz);
            }

            return map;
        }

        public String getReport() {
            StringBuilder info = new StringBuilder("---- " + RECORD_TYPE + " ----\n");

            for (String varName : variables.keySet()) {
                Object value = variables.get(varName);
                VariableInfo varInfo = metadata.getVariableInfo(varName);
                info.append(varInfo.typeName + " " + varName + " = " + value + "\n");
            }

            info.setLength(info.length() - 1);
            return info.toString();
        }

        // overrides

        @Override
        public String toString() {
            return "SerialCompiler.Record::" + RECORD_TYPE;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof Record record
                    && this.getCompiler() == record.getCompiler()
                    && RECORD_TYPE.equals(record.RECORD_TYPE)) {

                for (String key : record.variables.keySet()) { // same compiler + same type -> same keys
                    if (!variables.get(key).equals(record.variables.get(key))) return false;
                }
                return true;
            }
            return false;
        }

        @Override
        public int hashCode() {
            int hash = 31 * getCompiler().hashCode() + RECORD_TYPE.hashCode();
            for (Object obj : variables.values()) {
                hash = 31 * hash + obj.hashCode();
            }
            return hash;
        }
    }

    private static class Serializer {
        private final int SIGNATURE_BASE = 0xFE_53_52_4C;
        private final int SIGNATURE_RECORD = SIGNATURE_BASE | 0x01_00_00_00;
        private final int SIGNATURE_STRUCT = SIGNATURE_BASE | 0x00_00_00_00;
        private final int HEADER_SIZE = 7; // SIGNATURE[4B] + HASH[3B]

        private final SerialCompiler compiler;
        private final RecordInfo metadata;

        public Serializer(SerialCompiler compiler) {
            this(compiler, null);
        }

        public Serializer(SerialCompiler compiler, RecordInfo metadata) {
            this.compiler = compiler;
            this.metadata = metadata;
        }

        public byte[] serialize(Record record) {
            if (metadata == null) throw new IllegalArgumentException("Cannot serialize objects without metadata.");

            if (!record.RECORD_TYPE.equals(metadata.RECORD_TYPE))
                throw new IllegalArgumentException("This object can only serialize records of type '" +
                        metadata.RECORD_TYPE + "'.");

            return compiler.compressNullSafe(metadata.IS_RECORD ?
                    serializeRecord(record) : serializeStruct(record));
        }

        public Record deserialize(byte[] bytes) {
            byte[] plainBytes = compiler.decompressNullSafe(bytes);

            if (plainBytes.length >= HEADER_SIZE) {
                PrimitiveLike<Integer> intExtractor = new PrimitiveLike<>(Integer.class);
                int outerHeader = intExtractor.deserialize(plainBytes, 0);

                if ((outerHeader & 0xFE_FF_FF_FF) == SIGNATURE_BASE) {
                    int innerHeader = intExtractor.deserialize(plainBytes, 3);
                    int recordHash = innerHeader & 0x00_FF_FF_FF;

                    RecordInfo metadata;
                    if ((metadata = compiler.recordInfoFromHash(recordHash)) != null) {

                        if (outerHeader == SIGNATURE_RECORD && metadata.IS_RECORD)
                            return deserializeRecord(plainBytes, metadata);

                        if (outerHeader == SIGNATURE_STRUCT && !metadata.IS_RECORD)
                            return deserializeStruct(plainBytes, metadata);

                    }
                }
            }

            return compiler.makeRecord(EMPTY_RECORD);
        }

        private byte[] serializeRecord(Record record) {
            ArrayList<byte[]> multiBuffer = new ArrayList<>();
            DoubleHashMap<String, Integer> fieldHashMap = metadata.getHashingMapView();

            PrimitiveLike<Integer> intInjector = new PrimitiveLike<>(Integer.class);
            multiBuffer.add(intInjector.serialize(SIGNATURE_RECORD));
            multiBuffer.add(Arrays.copyOfRange(
                    intInjector.serialize(Utils.fastHash3B(record.RECORD_TYPE)),
                    1, 4));

            for (String fieldStr : fieldHashMap.keySet()) {
                HashLabel fieldLabel = new HashLabel(fieldStr);

                String fieldOrigin = fieldLabel.withEmptyIndex();
                VariableInfo varInfo = metadata.getVariableInfo(fieldOrigin);
                PrimitiveLike<?> prim = new PrimitiveLike<>(varInfo.clazz);

                int fieldHash = fieldHashMap.getValue(fieldStr);
                int[] sizes = metadata.getGroupSizes(fieldLabel);

                int sizeNeeded = Math.toIntExact(
                        Integer.BYTES +
                                (long) sizes.length * Integer.BYTES +
                                (long) Utils.arrayProduct(sizes) * prim.byteLength());

                byte sizeBinary = SizeReducer.sizeCompress(sizeNeeded);
                int sizeTotal = SizeReducer.sizeDecompress(sizeBinary);

                // 1. FIELD HEADER
                multiBuffer.add(intInjector.serialize(
                        fieldHash | ((sizeBinary & 0xFF) << 24)));

                // 2. DIMENSION ARRAY
                for (int size : sizes) {
                    multiBuffer.add(intInjector.serialize(size));
                }

                // 3. CONTENT ARRAY
                GroupIterator iterator = new GroupIterator(sizes);
                int[] iteration;
                while ((iteration = iterator.iterate()) != null) {
                    String varName = fieldLabel.withIndexes(iteration);
                    multiBuffer.add(prim.serializeObject(record.getValue(varName)));
                }

                // 4. PADDING
                int padding = sizeTotal - sizeNeeded;
                multiBuffer.add(new byte[padding]);
            }

            return Utils.megaConcat(multiBuffer);
        }

        private Record deserializeRecord(byte[] bytes, RecordInfo metadata) {
            Record result = compiler.makeRecord(metadata.RECORD_TYPE);
            DoubleHashMap<String, Integer> fieldHashMap = metadata.getHashingMapView();

            PrimitiveLike<Integer> intExtractor = new PrimitiveLike<>(Integer.class);

            long ptr = HEADER_SIZE;
            while (ptr + 4 <= (long) bytes.length) {

                // 1. FIELD HEADER
                int header = intExtractor.deserialize(bytes, (int) ptr);
                ptr += 4;

                byte lenByte = (byte) ((header & 0xFF_00_00_00) >> 24);
                int lenReal = SizeReducer.sizeDecompress(lenByte);

                long ptrEnd = Math.min(bytes.length, (ptr - 4) + lenReal);

                int hash = header & 0x00_FF_FF_FF;
                String fieldStr = fieldHashMap.getKey(hash);

                if (fieldStr != null) {
                    HashLabel fieldLabel = new HashLabel(fieldStr);
                    int[] realSizes = metadata.getGroupSizes(fieldLabel);

                    if (ptr + realSizes.length * (long) 4 <= ptrEnd) {

                        // 2. DIMENSION ARRAY
                        int[] tellSizes = new int[realSizes.length];

                        for (int i = 0; i < tellSizes.length; i++) {
                            tellSizes[i] = Math.max(0, intExtractor.deserialize(bytes, (int) ptr));
                            ptr += 4;
                        }

                        // 3. CONTENT ARRAY
                        Class<?> clazz = metadata.getVariableInfo(fieldLabel.withEmptyIndex()).clazz;
                        PrimitiveLike<?> prim = new PrimitiveLike<>(clazz);
                        int primSize = prim.byteLength();

                        GroupIterator iterator = new GroupIterator(tellSizes, realSizes);
                        int[] iteration;
                        while (ptr + primSize <= ptrEnd && (iteration = iterator.iterate()) != null) {

                            if (iterator.isIncluded()) { // field exists, include
                                Object obj = prim.deserialize(bytes, (int) ptr);
                                result.setValue(fieldLabel.withIndexes(iteration), obj);
                            }

                            ptr += primSize;
                        }

                        // 4. PADDING
                        // (ignore)
                    }
                }

                // jump to next element
                ptr = ptrEnd;
            }

            return result;
        }

        private byte[] serializeStruct(Record record) {
            ArrayList<byte[]> multiBuffer = new ArrayList<>();
            Set<String> variableList = metadata.getLinkedVariableSet();

            PrimitiveLike<Integer> intInjector = new PrimitiveLike<>(
                    Integer.class);

            multiBuffer.add(intInjector.serialize(SIGNATURE_STRUCT));
            multiBuffer.add(Arrays.copyOfRange(
                    intInjector.serialize(Utils.fastHash3B(record.RECORD_TYPE)),
                    1, 4));

            for (String varName : variableList) {
                VariableInfo varInfo = metadata.getVariableInfo(varName);
                PrimitiveLike<?> prim = new PrimitiveLike<>(varInfo.clazz);

                multiBuffer.add(
                        prim.serializeObject(record.getValue(varName))
                );
            }

            return Utils.megaConcat(multiBuffer);
        }

        private Record deserializeStruct(byte[] bytes, RecordInfo metadata) {
            Record result = compiler.makeRecord(metadata.RECORD_TYPE);
            Set<String> variableList = metadata.getLinkedVariableSet();

            long ptr = HEADER_SIZE;
            for (String varName : variableList) {
                VariableInfo varInfo = metadata.getVariableInfo(varName);
                PrimitiveLike<?> prim = new PrimitiveLike<>(varInfo.clazz);

                int size = prim.byteLength();
                if (ptr + size > bytes.length) break;

                result.setValue(varName, prim.deserialize(bytes, (int) ptr));
                ptr += size;
            }

            return result;
        }
    }

    public interface Compressor {
        // Shall compress binary data.
        byte[] compress(byte[] data);

        // Shall decompress binary data or return null when fails.
        byte[] decompress(byte[] data);
    }

    public static class SyntaxException extends Exception {
        private Integer line = null;

        protected SyntaxException(String message) {
            super(message);
        }

        protected void setLine(int line) {
            this.line = line;
        }

        public Integer getLine() {
            return line;
        }
    }

    public static class OperationException extends RuntimeException {
        protected OperationException(String message) {
            super(message);
        }
    }
}
