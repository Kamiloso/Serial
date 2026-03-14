package page.se3.serial;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import page.se3.serial.SerialCompiler.Record;
import page.se3.serial.SerialCompiler.SyntaxException;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

class SerialCompilerTest {

    // ======= SYNTAX TESTS ======= //

    @Nested
    class BasicOperations {

        @Test
        void shouldInitializeVariablesWithLet() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Player
                        LET int var1
                        LET int var2.var3 = 5
                    """);
            Record record = compiler.makeRecord("Player");

            Assertions.assertEquals(0, record.getValue("var1", Integer.class));
            Assertions.assertEquals(5, record.getValue("var2.var3", Integer.class));
        }

        @Test
        void shouldModifyValuesWithSet() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Player
                        LET int var1 = 1
                        LET int var2.var3 = 1
                        LET int arr[3] = 1 1 1
                        SET var1
                        SET var2.var3 = 5
                        SET arr = 1 4
                        SET arr[1] = 3
                    """);
            Record record = compiler.makeRecord("Player");

            Assertions.assertEquals(0, record.getValue("var1", Integer.class));
            Assertions.assertEquals(5, record.getValue("var2.var3", Integer.class));
            Assertions.assertArrayEquals(new Integer[]{1, 3, 0}, record.getArray("arr", Integer.class));
        }

        @Test
        void shouldHandleDeeplyNestedObjects() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Struct0
                        LET double var1
                    
                    RECORD Struct1
                        NEST Struct0 obj0
                    
                    RECORD Player
                        NEST Struct1 obj1
                        SET obj1.obj0.var1 = Infinity
                    """);
            Record record = compiler.makeRecord("Player");

            Assertions.assertEquals(Double.POSITIVE_INFINITY,
                    record.getValue("obj1.obj0.var1", Double.class));
        }

        @Test
        void shouldSupportInheritance() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Struct0a
                        LET double var1
                    
                    RECORD Struct0b
                        LET double var2
                    
                    RECORD Struct1
                        BASE Struct0a
                    
                    RECORD Player : Struct1 Struct0b
                        SET var1 = 4
                        SET var2 = -10
                    """);
            Record record = compiler.makeRecord("Player");

            Assertions.assertEquals(4.0, record.getValue("var1", Double.class));
            Assertions.assertEquals(-10.0, record.getValue("var2", Double.class));
        }

        @Test
        void shouldSupportDefDefineMacros() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    DEFINE $token = 11
                    DEFINE $mama 12
                    
                    // local scope -> won't replace
                    DEF Struct1 ...wrong...record...name...
                    
                    STRUCT Struct1
                        DEF 3 = 33
                        LET int a = $token
                        LET int b = $mama
                        LET int c = 3
                        LET int d = 4
                    
                    STRUCT Struct2
                        DEF 4 44
                        LET int e = $token
                        LET int f = $mama
                        LET int g = 3
                        LET int h = 4
                    """);
            Record struct1 = compiler.makeRecord("Struct1");
            Record struct2 = compiler.makeRecord("Struct2");

            Assertions.assertEquals(11, struct1.getValue("a", Integer.class));
            Assertions.assertEquals(12, struct1.getValue("b", Integer.class));
            Assertions.assertEquals(33, struct1.getValue("c", Integer.class));
            Assertions.assertEquals(4, struct1.getValue("d", Integer.class));

            Assertions.assertEquals(11, struct2.getValue("e", Integer.class));
            Assertions.assertEquals(12, struct2.getValue("f", Integer.class));
            Assertions.assertEquals(3, struct2.getValue("g", Integer.class));
            Assertions.assertEquals(44, struct2.getValue("h", Integer.class));
        }
    }

    @Nested
    class ArrayDeclarations {
        SerialCompiler compiler;
        Record record;

        @BeforeEach
        void init() throws SyntaxException {
            compiler = new SerialCompiler("""
                    RECORD Record
                        LET int arrayEmpty[5]
                        LET int arrayDefined[5] = 1 2 3
                        LET int arrayDotted[5] = 1 2 3 ...
                        LET int arrayFullDotted[5] = 1 2 3 4 5 ...
                        LET int arrayOnlyDots[5] = ...
                    
                        LET int arraySilent[0]
                        LET int arraySilent[99999999999999999999999]
                        LET int arraySilent = 1
                    """);

            record = compiler.makeRecord("Record");
        }

        @Test
        void empty() {
            Integer[] expected = {0, 0, 0, 0, 0};
            Integer[] actual = record.getArray("arrayEmpty", Integer.class);
            Assertions.assertArrayEquals(expected, actual);
        }

        @Test
        void defined() {
            Integer[] expected = {1, 2, 3, 0, 0};
            Integer[] actual = record.getArray("arrayDefined", Integer.class);
            Assertions.assertArrayEquals(expected, actual);
        }

        @Test
        void dotted() {
            Integer[] expected = {1, 2, 3, 3, 3};
            Integer[] actual = record.getArray("arrayDotted", Integer.class);
            Assertions.assertArrayEquals(expected, actual);
        }

        @Test
        void fullDotted() {
            Integer[] expected = {1, 2, 3, 4, 5};
            Integer[] actual = record.getArray("arrayFullDotted", Integer.class);
            Assertions.assertArrayEquals(expected, actual);
        }

        @Test
        void onlyDots() {
            Integer[] expected = {0, 0, 0, 0, 0};
            Integer[] actual = record.getArray("arrayOnlyDots", Integer.class);
            Assertions.assertArrayEquals(expected, actual);
        }

        @Test
        void shouldIgnoreEmptyAndStupidlyHugeArrays() {
            Integer[] expected = {};
            Integer[] actual = record.getArray("arraySilent", Integer.class);
            Assertions.assertArrayEquals(expected, actual);
            Assertions.assertEquals(1, record.getValue("arraySilent", Integer.class));
        }

        @Test
        void shouldDenyNegativeSizeArrays() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                LET int array[-5]
                            """));
        }
    }

    @Nested
    class StringDeclarations {
        SerialCompiler compiler;
        Record record;

        @BeforeEach
        void init() throws SyntaxException {
            compiler = new SerialCompiler("""
                    RECORD Record
                        LET char textUndefined[16]
                        LET char textEmpty[16] = ""
                        LET char textDefined[16] = "Contents"
                        LET char textNullTermination[16] = "\\u0000\\n\\u0000xxx\\u0000"
                        LET char textWrongSurrogates[16] = "Test\\uD83DTest\\uDD25"
                        LET char textPolish[16] = "Żółć ło emoji 😄"
                        LET char textAsArray[16] =   M A    K E ...
                        LET char textDoubleQuotes[16] = "'''\\"aaa\\"'''"
                    """);

            record = compiler.makeRecord("Record");
        }

        @Test
        void undefined() {
            String expected = "";
            String actual = record.getString("textUndefined");
            Assertions.assertEquals(expected, actual);
        }

        @Test
        void empty() {
            String expected = "";
            Assertions.assertEquals(expected, record.getString("textEmpty"));
        }

        @Test
        void defined() {
            String expected = "Contents";
            Assertions.assertEquals(expected, record.getString("textDefined"));
        }

        @Test
        void nullTermination() {
            String expected = "\u0000\n\u0000xxx";
            Assertions.assertEquals(expected, record.getString("textNullTermination"));
        }

        @Test
        void wrongSurrogatesDefaultForceUnicode() {
            String expected = "Test\uFFFDTest\uFFFD";
            Assertions.assertEquals(expected, record.getString("textWrongSurrogates"));
        }

        @Test
        void wrongSurrogatesExplicitIgnoreFix() {
            String expected = "Test\uD83DTest\uDD25";
            Assertions.assertEquals(expected, record.getString("textWrongSurrogates", false));
        }

        @Test
        void polish() {
            String expected = "Żółć ło emoji 😄";
            Assertions.assertEquals(expected, record.getString("textPolish"));
        }

        @Test
        void asArray() {
            String expected = "MAKEEEEEEEEEEEEE";
            Assertions.assertEquals(expected, record.getString("textAsArray"));
        }

        @Test
        void doubleQuotes() {
            String expected = "'''\"aaa\"'''";
            Assertions.assertEquals(expected, record.getString("textDoubleQuotes"));
        }
    }

    @Nested
    class PrimitiveParsingTests {
        SerialCompiler compiler;
        Record record;

        @BeforeEach
        void init() throws SyntaxException {
            compiler = new SerialCompiler("""
                    RECORD Player
                        LET byte _byte_ = -1
                        LET short _short_ = -1
                        LET int _int_ = -1
                        LET long _long_ = -1
                    
                        LET float _float_ = 1.5
                        LET float _float_e = -1.5e3
                        LET float _float_INF = Infinity
                        LET float _float_NaN = NaN
                    
                        LET double _double_ = 1.5
                        LET double _double_e = -1.5e3
                        LET double _double_INF = -Infinity
                        LET double _double_NaN = NaN
                    
                        LET char _char_1 = 'A'
                        LET char _char_2 = '\\u0041'
                        LET char _char_3 = %65
                        LET char _char_4 = A
                    
                        LET boolean _boolean_T = TrUe
                        LET boolean _boolean_1 = 1
                        LET boolean _boolean_F = FaLsE
                        LET boolean _boolean_0 = 0
                    """);

            record = compiler.makeRecord("Player");
        }

        @Nested
        class IntegerTypes {
            @Test
            void byteParsing() {
                Assertions.assertEquals((byte) -1, record.getValue("_byte_", Byte.class));
            }

            @Test
            void shortParsing() {
                Assertions.assertEquals((short) -1, record.getValue("_short_", Short.class));
            }

            @Test
            void intParsing() {
                Assertions.assertEquals(-1, record.getValue("_int_", Integer.class));
            }

            @Test
            void longParsing() {
                Assertions.assertEquals(-1L, record.getValue("_long_", Long.class));
            }
        }

        @Nested
        class FloatingPointTypes {
            @Test
            void floatParsing() {
                Assertions.assertEquals(1.5f, record.getValue("_float_", Float.class));
                Assertions.assertEquals(-1.5e3f, record.getValue("_float_e", Float.class));
                Assertions.assertTrue(record.getValue("_float_INF", Float.class).isInfinite());
                Assertions.assertTrue(record.getValue("_float_NaN", Float.class).isNaN());
            }

            @Test
            void doubleParsing() {
                Assertions.assertEquals(1.5, record.getValue("_double_", Double.class));
                Assertions.assertEquals(-1.5e3, record.getValue("_double_e", Double.class));
                Assertions.assertTrue(record.getValue("_double_INF", Double.class).isInfinite());
                Assertions.assertTrue(record.getValue("_double_NaN", Double.class).isNaN());
            }
        }

        @Nested
        class CharacterTypes {
            @Test
            void charParsing() {
                Assertions.assertEquals('A', record.getValue("_char_1", Character.class));
                Assertions.assertEquals('A', record.getValue("_char_2", Character.class));
                Assertions.assertEquals('A', record.getValue("_char_3", Character.class));
                Assertions.assertEquals('A', record.getValue("_char_4", Character.class));
            }
        }

        @Nested
        class BooleanTypes {
            @Test
            void booleanTrueParsing() {
                Assertions.assertTrue(record.getValue("_boolean_T", Boolean.class));
                Assertions.assertTrue(record.getValue("_boolean_1", Boolean.class));
            }

            @Test
            void booleanFalseParsing() {
                Assertions.assertFalse(record.getValue("_boolean_F", Boolean.class));
                Assertions.assertFalse(record.getValue("_boolean_0", Boolean.class));
            }
        }
    }

    @Nested
    class Deprecation {

        @Test
        void variablesAreErased() throws SyntaxException {
            String code = """
                    RECORD Player
                        LET int a = 1
                        __LET int b = 2
                    """;

            SerialCompiler compiler = new SerialCompiler(code);
            Record record = compiler.makeRecord("Player");

            Assertions.assertEquals(1, record.getValue("a", Integer.class));
            Assertions.assertNull(record.getValue("b", Integer.class));
        }

        @Test
        void detectsHashCollisions() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Player
                                LET int buckeroo = 1
                                __LET int plumless = 2
                            """));
        }
    }

    @Nested
    class Ensuring {

        @Test
        void deserializerFixesOnlyWhenNeeded() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                         LET int a = 5
                         ENSURE a >= 0
                    """);
            Record defaultRecord = compiler.makeRecord("Record");
            Record modifiedRecord = defaultRecord
                    .clone()
                    .setValue("a", -5);

            byte[] bytes = modifiedRecord.serialize();

            // default -> FIX
            Record record1 = compiler.makeRecord(bytes);
            Assertions.assertEquals(defaultRecord, record1);

            // true -> FIX
            Record record2 = compiler.makeRecord(bytes, true);
            Assertions.assertEquals(defaultRecord, record2);

            // false -> NOT FIX
            Record record3 = compiler.makeRecord(bytes, false);
            Assertions.assertEquals(modifiedRecord, record3);
        }

        @Test
        void atomicConditions() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                         LET int a[6] = 12 10 8 10 10 -99
                         ENSURE a[0] > 10
                         ENSURE a[1] >= 10
                         ENSURE a[2] < 10
                         ENSURE a[3] <= 10
                         ENSURE a[4] == 10
                         ENSURE a[5] != 10
                    
                         LET int not = 5
                         ENSURE not NOT < 0
                    
                         LET byte b ENSURE b >= 0
                         LET short s ENSURE s >= 0
                         LET int i ENSURE i >= 0
                         LET long l ENSURE l >= 0
                         LET float f ENSURE f >= 0
                         LET double d ENSURE d >= 0
                         LET char c = 'C' ENSURE c >= 'B'
                         LET boolean bl ENSURE bl == FaLsE
                    """);
            Record record = compiler.makeRecord("Record");
            Record recordCopy = record.clone();

            record.setArray("a", new Integer[]{-100, -100, 100, 100, -100, 10});
            record.setValue("not", -100);
            record.setValue("b", (byte) -1);
            record.setValue("s", (short) -1);
            record.setValue("i", -1);
            record.setValue("l", -1L);
            record.setValue("f", -1.0f);
            record.setValue("d", -1.0);
            record.setValue("c", 'A');
            record.setValue("bl", true);

            Assertions.assertTrue(noFieldEquals(recordCopy, record));
            record.checkAndRepair();
            Assertions.assertEquals(recordCopy, record);
        }

        @Test
        void floatingPointConditions() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                         LET float f_nan = NaN ENSURE f_nan IS NAN
                         LET float f_inf = -Infinity ENSURE f_inf IS INFINITE
                         LET float f_fin = 5.0 ENSURE f_fin IS FINITE
                         LET double d_nan = NaN ENSURE d_nan IS NAN
                         LET double d_inf = -Infinity ENSURE d_inf IS INFINITE
                         LET double d_fin = 5.0 ENSURE d_fin IS FINITE
                    """);
            Record record = compiler.makeRecord("Record");
            Record recordCopy = record.clone();

            record.setValue("f_nan", 1.0f);
            record.setValue("f_inf", 1.0f);
            record.setValue("f_fin", Float.POSITIVE_INFINITY);
            record.setValue("d_nan", 1.0);
            record.setValue("d_inf", 1.0);
            record.setValue("d_fin", Double.POSITIVE_INFINITY);

            Assertions.assertTrue(noFieldEquals(recordCopy, record));
            record.checkAndRepair();
            Assertions.assertEquals(recordCopy, record);
        }

        @Test
        void properNaNBehaviour() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                         LET float f = NaN
                         LET double d = NaN
                    
                         ENSURE f NOT == NaN
                         ENSURE f NOT > NaN
                         ENSURE f NOT >= NaN
                         ENSURE f NOT < NaN
                         ENSURE f NOT <= NaN
                         ENSURE f != NaN
                         ENSURE f IS NAN
                    
                         ENSURE d NOT == NaN
                         ENSURE d NOT > NaN
                         ENSURE d NOT >= NaN
                         ENSURE d NOT < NaN
                         ENSURE d NOT <= NaN
                         ENSURE d != NaN
                         ENSURE d IS NAN
                    """);
            compiler.makeRecord("Record");
        }

        @Test
        void arrayConditions() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                         LET char array[16] = A B C D
                         ENSURE array >= 'A' AND <= 'Z' OR == %0
                    """);
            Record record = compiler.makeRecord("Record");

            record.setValue("array[1]", 'E');
            record.setValue("array[2]", '.');
            record.setValue("array[4]", 'E');

            Assertions.assertEquals("AE.DE", record.getString("array"));
            record.checkAndRepair();
            Assertions.assertEquals("AECDE", record.getString("array"));
        }

        @Test
        void stringConditions() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                         LET char array[16] = "ABCD"
                         ENSURE array MATCHES "^[A-Z\\\\u0000]*$"
                    """);
            Record record = compiler.makeRecord("Record");

            record.setValue("array[1]", 'E');
            record.setValue("array[2]", '.');
            record.setValue("array[4]", 'E');

            Assertions.assertEquals("AE.DE", record.getString("array"));
            record.checkAndRepair();
            Assertions.assertEquals("ABCD", record.getString("array"));
        }

        @Test
        void stringEquals() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                         LET char array[16] = "abcd"
                         ENSURE array NOT EQUALS "abcde"
                    """);
            Record record = compiler.makeRecord("Record");

            record.setValue("array[4]", 'e');

            Assertions.assertEquals("abcde", record.getString("array"));
            record.checkAndRepair();
            Assertions.assertEquals("abcd", record.getString("array"));
        }

        @Test
        void supportsUnicodeRegex() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                         LET char array[16] = "ABC"
                         ENSURE array MATCHES "^[\\\\p{L}]*$"
                    
                         LET char array2[16] = "Słowacki"
                         ENSURE array2 MATCHES ".*ł.*"
                    """);

            Record record = compiler.makeRecord("Record");
            record.setString("array", "ĄłŻ");

            // not fixing
            Assertions.assertEquals("ĄłŻ", record.getString("array"));
            record.checkAndRepair();
            Assertions.assertEquals("ĄłŻ", record.getString("array"));

            record.setValue("array[1]", '1');

            // fixing
            Assertions.assertEquals("Ą1Ż", record.getString("array"));
            record.checkAndRepair();
            Assertions.assertEquals("ABC", record.getString("array"));
        }

        @Test
        void complexConditionsAndConditionInheritance() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD ValidNumber
                         LET int value = 8
                         LET int co_value = 100
                         ENSURE value >= -10 AND <= -5 OR >= 5 AND <= 10
                         ENSURE value >= -9 AND <= -4 OR >= 4 AND <= 9
                         ENSURE co_value != 0
                    
                    RECORD ValidNumber_NEST
                         NEST ValidNumber nest
                    
                    RECORD ValidNumber_BASE
                        BASE ValidNumber
                    """);
            Record record;

            // @formatter:off

            record = compiler.makeRecord("ValidNumber_NEST");
            record.setValue("nest.co_value", 200);
            record.setValue("nest.value", -10); Assertions.assertTrue(record.checkAndRepair());
            record.setValue("nest.value", -9); Assertions.assertFalse(record.checkAndRepair());
            record.setValue("nest.value", 0); Assertions.assertTrue(record.checkAndRepair());
            record.setValue("nest.value", 4); Assertions.assertTrue(record.checkAndRepair());
            Assertions.assertEquals(8, record.getValue("nest.value"));
            record.setValue("nest.value", 5); Assertions.assertFalse(record.checkAndRepair());
            Assertions.assertEquals(200, record.getValue("nest.co_value"));
            record.setValue("nest.co_value", 1); Assertions.assertFalse(record.checkAndRepair());
            record.setValue("nest.co_value", 0); Assertions.assertTrue(record.checkAndRepair());
            Assertions.assertEquals(100, record.getValue("nest.co_value"));

            record = compiler.makeRecord("ValidNumber_BASE");
            record.setValue("co_value", 200);
            record.setValue("value", -10); Assertions.assertTrue(record.checkAndRepair());
            record.setValue("value", -9); Assertions.assertFalse(record.checkAndRepair());
            record.setValue("value", 0); Assertions.assertTrue(record.checkAndRepair());
            record.setValue("value", 4); Assertions.assertTrue(record.checkAndRepair());
            Assertions.assertEquals(8, record.getValue("value"));
            record.setValue("value", 5); Assertions.assertFalse(record.checkAndRepair());
            Assertions.assertEquals(200, record.getValue("co_value"));
            record.setValue("co_value", 1); Assertions.assertFalse(record.checkAndRepair());
            record.setValue("co_value", 0); Assertions.assertTrue(record.checkAndRepair());
            Assertions.assertEquals(100, record.getValue("co_value"));

            record = compiler.makeRecord("ValidNumber");
            record.setValue("co_value", 200);
            record.setValue("value", -10); Assertions.assertTrue(record.checkAndRepair());
            record.setValue("value", -9); Assertions.assertFalse(record.checkAndRepair());
            record.setValue("value", 0); Assertions.assertTrue(record.checkAndRepair());
            record.setValue("value", 4); Assertions.assertTrue(record.checkAndRepair());
            Assertions.assertEquals(8, record.getValue("value"));
            record.setValue("value", 5); Assertions.assertFalse(record.checkAndRepair());
            Assertions.assertEquals(200, record.getValue("co_value"));
            record.setValue("co_value", 1); Assertions.assertFalse(record.checkAndRepair());
            record.setValue("co_value", 0); Assertions.assertTrue(record.checkAndRepair());
            Assertions.assertEquals(100, record.getValue("co_value"));

            // @formatter:on
        }

        @Test
        void detectsIncompatibleComparisons() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Record
                                LET char text[16] = "HELLO"
                                ENSURE text MATCHES "^[A-Z]*$" AND >= 'A' AND <= 'Z'
                            """));
        }

        @Test
        void detectsInvalidDefaults() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Record
                                LET int a = 5
                                ENSURE a < 0
                            """));
        }

        @Test
        void detectsInvalidRegex() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Record
                                LET char text[16] = "("
                                ENSURE text MATCHES "("
                            """));
        }

        @Test
        void detectsInvalidParsing() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Record
                                LET int a = 5
                                ENSURE a >= 0xxx
                            """));
        }

        @Test
        void throwsAtRuntimeWhenConditionBranchIsEvaluated() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                        LET int a = 5
                        ENSURE a > 0 OR >= xxx
                    """);
            Record record = compiler.makeRecord("Record");
            record.checkAndRepair();

            record.setValue("a", -5);
            Assertions.assertThrows(SerialCompiler.OperationException.class, record::checkAndRepair);
        }

        private boolean noFieldEquals(Record record1, Record record2) {
            if (record1.getCompiler() == record2.getCompiler() &&
                    record1.RECORD_TYPE.equals(record2.RECORD_TYPE)) {

                for (var kvp : record1.getOrderedVariables().entrySet()) {
                    String key = kvp.getKey();
                    Class<?> clazz = kvp.getValue();

                    Object obj1 = record1.getValue(key, clazz);
                    Object obj2 = record2.getValue(key, clazz);

                    if (obj1.equals(obj2)) return false;
                }
                return true;
            }
            return false;
        }
    }

    @Nested
    class IllegalSyntax {
        @Test
        void tildaIsNotAllowed() {
            Assertions.assertThrows(
                    SyntaxException.class,
                    () -> {
                        String code = """
                                DEFINE ~ = 2
                                
                                RECORD Player
                                    LET int var1 = ~
                                    LET int var2 = 5
                                """;

                        new SerialCompiler(code);
                    });
        }

        @Test
        void shouldDenyDeclarationsOutsideRecord() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            LET int var1
                            
                            RECORD Other
                                LET int var2
                            """));
        }

        @Test
        void shouldDetectDiamondProblem() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Base
                                LET short a
                                LET short b = 1
                            
                            RECORD Base1 : Base
                            RECORD Base2 : Base
                            
                            RECORD Struct : Base1 Base2
                                LET short c = 2
                            """));
        }

        @Test
        void shouldDenyExplicitNestedDeclarationInLet() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                LET int a[2].b = 5
                            """));
        }

        @Test
        void shouldDenyExplicitNestedDeclarationInNest() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                NEST __EMPTY_RECORD__ nested[2].var
                            """));
        }

        @Test
        void shouldDetectWrongVariableNamePattern() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                LET int 2var
                            """));
        }

        @Test
        void shouldDetectWrongRecordNamePattern() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD 2rec
                                LET int a
                            """));

            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            STRUCT 2rec
                                LET int a
                            """));
        }

        @Test
        void declaringVariableIntoArrayNameCollision() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Base
                                LET int a[10]
                            
                            RECORD Struct : Base
                                LET double a = 3
                            """));
        }

        @Test
        void declaringArrayIntoVariableNameCollision() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Base
                                LET int a = 3
                            
                            RECORD Struct : Base
                                LET int a[10]
                            """));
        }

        @Test
        void shouldDetectArrayDeclarationOverflow() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Player
                                LET int array[2] = 1 2 3
                            """));
        }

        @Test
        void shouldDetectStringDeclarationOverflow() {
            Assertions.assertThrows(
                    SyntaxException.class,
                    () -> {
                        String code = """
                                RECORD Player
                                    LET char array[8] = "A very very very long string."
                                """;

                        new SerialCompiler(code);
                    });
        }

        @Test
        void shouldDetectWrongStringEscaping() {
            Assertions.assertThrows(
                    SyntaxException.class,
                    () -> {
                        String code = """
                                RECORD Player
                                    LET char text[16] = "Aaa\\u"
                                """;

                        new SerialCompiler(code);
                    });
        }

        @Test
        void shouldDetectWrongQuotes() {
            Assertions.assertThrows(
                    SyntaxException.class,
                    () -> {
                        String code = """
                                RECORD Player
                                    LET char text[16] = "AB"cd"
                                """;

                        new SerialCompiler(code);
                    });
        }

        @Test
        void shouldBlockFieldHashCollisions() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Player
                                // same CRC32 hash:
                                LET int plumless[16] = 1 2 3 ...
                                LET int buckeroo[8] = 1 2 ...
                            """));
        }

        @Test
        void shouldBlockRecordHashCollisions() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD plumless
                            RECORD buckeroo
                            """));
        }

        @Test
        void denyOverwritingUndefinedVariables() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                SET non_existing = 1
                            """));
        }

        @Test
        void denyInheritanceFromItself() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                BASE Struct
                            """));
        }

        @Test
        void denyNestingInsideItself() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                NEST Struct nest
                            """));
        }

        @Test
        void denyMultipleTokenDefinitionsInLet() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                LET char text[64] = "abc" "def"
                            """));
        }

        @Test
        void denyMultipleTokenDefinitionsInSet() {
            Assertions.assertThrows(SyntaxException.class,
                    () -> new SerialCompiler("""
                            RECORD Struct
                                LET char text[64]
                                SET text = "abc" "def"
                            """));
        }
    }

    @Nested
    class OtherAssumptions {

        @Test
        void emptyRecordAlwaysExists() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("");
            Assertions.assertEquals("__EMPTY_RECORD__",
                    compiler.makeRecord("__EMPTY_RECORD__").RECORD_TYPE);
        }

        @Test
        void removesCommentsBeforeCompilation() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    /*WRONG COMMAND*/
                    
                    // WRONG COMMAND
                    //WRONG COMMAND
                    
                    // // WRONG COMMAND
                    
                    RECORD Record
                        LET int var = 5
                    
                    /* WRONG COMMAND
                    // WRONG COMMAND */
                    
                    """);
            Record record = compiler.makeRecord("Record");
            Assertions.assertEquals(5, record.getValue("var", Integer.class));
        }

        @Test
        void handlesMultipleWhitespace() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                        LET int     var =  5
                    """);
            Record record = compiler.makeRecord("Record");
            Assertions.assertEquals(5, record.getValue("var", Integer.class));
        }

        @Test
        void parsesStringsCorrectly() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Record
                        LET char text1[64] = ""
                        LET char text2[64] = "basic"
                        LET char text3[64] = "  with spaces "
                        LET char text4[64] = "C:\\\\Temp\\\\"
                        LET char text5[64] = " : hello  world "
                        LET char text6[64] = "\\n\\n\\n\\\\"
                        LET char text7[64] = " = hello  world "
                        LET char text8[64] = "\\0\\d\\t\\\\"
                        LET char text9[64] = "a b c "xxx" d e f"
                    """);
            Record record = compiler.makeRecord("Record");

            Assertions.assertEquals("", record.getString("text1"));
            Assertions.assertEquals("basic", record.getString("text2"));
            Assertions.assertEquals("  with spaces ", record.getString("text3"));
            Assertions.assertEquals("C:\\Temp\\", record.getString("text4"));
            Assertions.assertEquals(" : hello  world ", record.getString("text5"));
            Assertions.assertEquals("\n\n\n\\", record.getString("text6"));
            Assertions.assertEquals(" = hello  world ", record.getString("text7"));
            Assertions.assertEquals("a b c \"xxx\" d e f", record.getString("text9"));
        }

        @Test
        void detectsErrorLineCorrectly() {
            String code = """
                    RECORD Struct
                        LET int a = 1
                        __LET int b = 2
                        //LET int c = 3
                        LT int d = 4
                        LET int e = 5
                    """;

            try {
                new SerialCompiler(code);

            } catch (SyntaxException ex) {
                Assertions.assertEquals(5, ex.getLine());
                return;
            }

            Assertions.fail("No SyntaxException was thrown.");
        }
    }

    // ======= API TESTS ======= //

    @Nested
    class ApiTests {
        SerialCompiler compiler;
        Record record;

        @BeforeEach
        void setup() throws SyntaxException {
            compiler = new SerialCompiler("""
                    RECORD Nested
                        LET char name[5] = 1 2 3
                        LET int variable = 999
                    
                    RECORD Main
                        LET int variable = 1
                        NEST Nested nested[5]
                        LET char mystring[16] = "Test"
                    
                    RECORD Main2
                        LET int variable = 5
                        LET char mystring[2] = ""
                    """);
            record = compiler.makeRecord("Main");
        }

        @Test
        void valueAccessAndMutate() {
            Assertions.assertThrows(SerialCompiler.OperationException.class,
                    () -> record.getValue("variable", int.class));

            Assertions.assertNull(record.getValue("_", Integer.class));
            Assertions.assertNull(record.getValue("variable", Boolean.class));
            Assertions.assertNull(record.getValue("variable", Record.class));

            Assertions.assertEquals(1, record.getValue("variable", Integer.class));
            record.setValue("variable", 2);
            Assertions.assertEquals(2, record.getValue("variable", Integer.class));
        }

        @Test
        void arrayAccess() {
            Assertions.assertThrows(SerialCompiler.OperationException.class,
                    () -> record.getArray("nested[0].name", int.class));

            Assertions.assertEquals(0, record.getArray("_", Character.class).length);
            Assertions.assertEquals(0, record.getArray("nested[0].name", Boolean.class).length);
            Assertions.assertEquals(0, record.getArray("nested[0].name", Record.class).length);
            Assertions.assertEquals(0, record.getArray("nested[0].name[0]", Character.class).length);

            Assertions.assertArrayEquals(
                    new Character[]{'1', '2', '3', 0, 0},
                    record.getArray("nested[0].name", Character.class));
        }

        @Test
        void arrayMutationsAndBounds() {
            record.setArray("nested[0].name", new Character[]{'1', '2', '3', '4', '5'});

            Assertions.assertArrayEquals(
                    new Character[]{'1', '2', '3', '4', '5'},
                    record.getArray("nested[0].name", Character.class));

            Assertions.assertThrows(SerialCompiler.OperationException.class,
                    () -> record.setArray("nested[0].name", new Character[]{'1', '2', '3'}));

            Assertions.assertThrows(SerialCompiler.OperationException.class,
                    () -> record.setArray("nested[0].name", new Character[]{'1', '2', '3', '4', '5', '6'}));

            record.setArray("_", new Object[0]);
        }

        @Test
        void stringHandling() {
            Assertions.assertEquals("", record.getString("_"));
            Assertions.assertEquals("", record.getString("_", false));

            Assertions.assertEquals("Test", record.getString("mystring"));
            Assertions.assertEquals("Test", record.getString("mystring", false));

            record.setString("mystring", "Test\0\nTest");
            Assertions.assertEquals("Test\0\nTest", record.getString("mystring", true));
            Assertions.assertEquals("Test\0\nTest", record.getString("mystring", false));
        }

        @Test
        void stringSurrogatesAndLimits() {
            record.setString("mystring", "Test\uD83DTest\0\0");
            Assertions.assertEquals("Test\uFFFDTest", record.getString("mystring", true));
            Assertions.assertEquals("Test\uD83DTest", record.getString("mystring", false));

            record.setString("mystring", "Test\uDD25Test\0");
            Assertions.assertEquals("Test\uFFFDTest", record.getString("mystring", true));
            Assertions.assertEquals("Test\uDD25Test", record.getString("mystring", false));

            Assertions.assertThrows(SerialCompiler.OperationException.class,
                    () -> record.setString("mystring", "Toooooooooooooooooooooooooooooooooooooooooooooooo"));

            record.setString("_", "");
        }

        @Test
        void nestedAccess() {
            Record taken = record.getRecord("nested[0]", "Nested");
            Assertions.assertEquals('2', taken.getValue("name[1]", Character.class));

            taken.setValue("name[1]", 'A');
            Assertions.assertEquals('A', taken.getValue("name[1]", Character.class));
            Assertions.assertEquals('2', record.getValue("nested[0].name[1]", Character.class));

            record.setRecord("nested[0]", taken);
            Assertions.assertEquals('A', taken.getValue("name[1]", Character.class));
            Assertions.assertEquals('A', record.getValue("nested[0].name[1]", Character.class));
        }

        @Test
        void virtualPaths() {
            Assertions.assertThrows(SerialCompiler.OperationException.class,
                    () -> record.getRecord("nested[0]", "non-existent"));

            Record madeEmpty = compiler.makeRecord("__EMPTY_RECORD__");

            record.setRecord("_", record);
            Assertions.assertEquals(
                    madeEmpty,
                    record.getRecord("_", "__EMPTY_RECORD__"));

            Record recordB = record.getRecord("nested[0]", "Main");
            Assertions.assertEquals(999, recordB.getValue("variable"));
        }

        @Test
        void casting() {
            Assertions.assertThrows(SerialCompiler.OperationException.class,
                    () -> record.castTo("_"));

            Record main2 = record.castTo("Main2");
            Assertions.assertEquals(1, main2.getValue("variable", Integer.class));
            Assertions.assertEquals("Te", main2.getString("mystring"));

            main2.setValue("variable", 45);
            main2.setString("mystring", "AB");
            Assertions.assertNull(main2.getValue("nested[0].name[1]"));

            Record mainA = main2.castTo("Main");
            Assertions.assertEquals(45, mainA.getValue("variable", Integer.class));
            Assertions.assertEquals("ABst", mainA.getString("mystring"));
            Assertions.assertEquals('2', mainA.getValue("nested[0].name[1]"));
        }

        @Test
        void cloneAndEquality() {
            record.setValue("variable", 11);
            Record cloned = record.clone();

            Assertions.assertTrue(cloned.equals(record) && record.equals(cloned));
            cloned.setValue("variable", 12);
            Assertions.assertFalse(cloned.equals(record) || record.equals(cloned));

            Record main2 = record.castTo("Main2");
            Assertions.assertFalse(main2.equals(record) || record.equals(main2));

            Assertions.assertNotEquals(null, record);
        }

        @Nested
        class ResetOperations {
            SerialCompiler compiler;
            Record record;

            @BeforeEach
            void setup() throws SyntaxException {
                compiler = new SerialCompiler("""
                        RECORD Nested
                            LET char name[5] = 1 2 3
                            LET int variable = 999
                        
                        RECORD Main
                            LET int variable = 1
                            NEST Nested nested[5]
                            LET char mystring[16] = "Test"
                        """);
                record = compiler.makeRecord("Main");
            }

            @Test
            void resetValue() {
                record.setValue("variable", 123);
                Assertions.assertEquals(123, record.getValue("variable", Integer.class));

                record.resetValue("variable");
                Assertions.assertEquals(1, record.getValue("variable", Integer.class));
            }

            @Test
            void resetArray() {
                record.setString("mystring", "Zmieniony");
                Assertions.assertEquals("Zmieniony", record.getString("mystring"));

                record.resetArray("mystring");
                Assertions.assertEquals("Test", record.getString("mystring"));
            }

            @Test
            void resetTreeOnSingleNestedRecord() {
                String path = "nested[0].variable";

                record.setValue(path, -50);
                Assertions.assertEquals(-50, record.getValue(path, Integer.class));

                record.resetTree("nested[0]");
                Assertions.assertEquals(999, record.getValue(path, Integer.class));

                record.setValue("nested[1].variable", 10);
                record.resetTree("nested[0]");
                Assertions.assertEquals(10, record.getValue("nested[1].variable", Integer.class));
            }

            @Test
            void resetTreeOnWholeBranch() {
                record.setValue("nested[2].variable", 0);
                record.setValue("nested[2].name[0]", 'X');
                record.setValue("nested[4].variable", 0);

                record.resetTree("nested");

                Assertions.assertEquals(999, record.getValue("nested[2].variable", Integer.class));
                Assertions.assertEquals('1', record.getValue("nested[2].name[0]", Character.class));
                Assertions.assertEquals(999, record.getValue("nested[4].variable", Integer.class));
            }

            @Test
            void resetEmptyOrWrongPath() {
                int hashBefore = record.hashCode();

                record.resetValue("notexistsme");
                record.resetTree("not.exists.me");

                Assertions.assertEquals(hashBefore, record.hashCode());
            }
        }

        @Nested
        class NullSafety {
            Record structRecord;

            @BeforeEach
            void init() throws SyntaxException {
                compiler = new SerialCompiler(
                        """
                                RECORD Nested
                                    LET char var
                                
                                RECORD Struct
                                    LET int array[5]
                                    NEST Nested nested[5]
                                """);
                structRecord = compiler.makeRecord("Struct");
            }

            @Test
            void testNullPointers() {
                assertAllBlock(
                        "Compiler",
                        () -> new SerialCompiler(null),
                        () -> new SerialCompiler("", null));

                assertAllBlock(
                        "Record",
                        () -> compiler.makeRecord((byte[]) null),
                        () -> compiler.makeRecord((String) null));

                assertAllBlock(
                        "Value",
                        () -> structRecord.getValue(null),
                        () -> structRecord.getValue("sth", null),
                        () -> structRecord.getValue(null, Integer.class),
                        () -> structRecord.setValue(null, new Object()),
                        () -> structRecord.setValue("sth", null));

                assertAllBlock(
                        "Array",
                        () -> structRecord.getArray(null, Integer.class),
                        () -> structRecord.getArray("sth", null),
                        () -> structRecord.setArray(null, new Object[5]),
                        () -> structRecord.setArray("sth", null));

                assertAllBlock(
                        "String",
                        () -> structRecord.getString(null),
                        () -> structRecord.getString(null, false),
                        () -> structRecord.setString(null, "x"),
                        () -> structRecord.setString("sth", null));

                assertAllBlock(
                        "Record",
                        () -> structRecord.getRecord(null, "rectype"),
                        () -> structRecord.getRecord("sth", null),
                        () -> structRecord.setRecord(null, structRecord),
                        () -> structRecord.setRecord("sth", null));

                assertAllBlock(
                        "Resets",
                        () -> structRecord.resetValue(null),
                        () -> structRecord.resetArray(null),
                        () -> structRecord.resetTree(null));

                assertAllBlock(
                        "NestedNulls",
                        () -> structRecord.setArray("array", new Integer[5]));
            }
        }

        private void assertAllBlock(String name, Executable... executables) {
            for (int i = 0; i < executables.length; i++) {
                try {
                    Assertions.assertThrows(NullPointerException.class, executables[i]);
                } catch (AssertionError e) {
                    throw new AssertionError(name + " segment failed at index " + i, e);
                }
            }
        }
    }

    @Nested
    class Serialization {

        @Test
        void allPrimitivesAndArrays() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Struct
                        LET byte _byte_
                        LET short _short_
                        LET int _int_
                        LET long _long_
                        LET float _float_
                        LET double _double_
                        LET char _char_
                        LET boolean _boolean_
                        LET byte _byte_A[3]
                        LET short _short_A[3]
                        LET int _int_A[3]
                        LET long _long_A[3]
                        LET float _float_A[3]
                        LET double _double_A[3]
                        LET char _char_A[3]
                        LET boolean _boolean_A[3]
                    """);
            Record record = compiler.makeRecord("Struct");

            testRoundTrip(record);

            record.setValue("_byte_", (byte) 123);
            record.setValue("_short_", (short) 32100);
            record.setValue("_int_", 1234567890);
            record.setValue("_long_", 98765432109874321L);
            record.setValue("_float_", 123.456f);
            record.setValue("_double_", 9876.54321);
            record.setValue("_char_", 'Z');
            record.setValue("_boolean_", true);

            record.setArray("_byte_A", new Byte[]{1, 2, 3});
            record.setArray("_short_A", new Short[]{100, 200, 300});
            record.setArray("_int_A", new Integer[]{1000, 2000, 3000});
            record.setArray("_long_A", new Long[]{10000L, 20000L, 30000L});
            record.setArray("_float_A", new Float[]{1.1f, 2.2f, 3.3f});
            record.setArray("_double_A", new Double[]{10.1, 20.2, 30.3});
            record.setArray("_char_A", new Character[]{'A', '\uDD25', '\0'});
            record.setArray("_boolean_A", new Boolean[]{true, false, true});

            testRoundTrip(record);
        }

        @Test
        void highlyNestedStruct() throws SyntaxException {
            SerialCompiler compiler = new SerialCompiler("""
                    RECORD Tree3
                        LET char text[17]
                        LET int variable
                    
                    RECORD Tree2
                        NEST Tree3 tree[13]
                    
                    RECORD Tree1
                        NEST Tree2 tree[7]
                    
                    RECORD Struct
                        LET double dbl = Infinity
                        NEST Tree1 tree[5]
                    """);
            Record record = compiler.makeRecord("Struct");

            testRoundTrip(record);

            record.setValue("dbl", Double.NEGATIVE_INFINITY);
            record.setString("tree[1].tree[6].tree[3].text", "Mama");
            record.setString("tree[4].tree[1].tree[12].text", "Tata");
            record.setValue("tree[0].tree[0].tree[0].variable", 5);
            record.setRecord("tree[2]", record.getRecord("tree[4]", "Tree1"));

            testRoundTrip(record);
        }

        @Test
        void addedNewFieldsAndChangedOrder() throws SyntaxException {
            SerialCompiler oldCompiler = new SerialCompiler("""
                    STRUCT Struct
                        LET int a = 3
                        LET int b = 4
                        LET int c = 5
                    
                    RECORD Record
                        NEST Struct s
                        LET int a = 3
                    """);

            SerialCompiler newCompiler = new SerialCompiler("""
                    STRUCT Struct
                        LET int a = -3
                        LET int b = -4
                        LET int c = -5
                        LET char text[16] = "Hello"
                        LET int d = -6
                    
                    RECORD Record
                        LET int a = -3
                        LET char text[16] = "Hello"
                        NEST Struct s
                        LET int d = -6
                    """);

            Record oldStruct = oldCompiler.makeRecord("Struct");
            Record oldRecord = oldCompiler.makeRecord("Record");

            byte[] structBytes = oldStruct.serialize();
            byte[] recordBytes = oldRecord.serialize();

            Record newStruct = newCompiler.makeRecord(structBytes);
            Record newRecord = newCompiler.makeRecord(recordBytes);

            // struct
            Assertions.assertEquals(3, newStruct.getValue("a"));
            Assertions.assertEquals(4, newStruct.getValue("b"));
            Assertions.assertEquals(5, newStruct.getValue("c"));
            Assertions.assertEquals("Hello", newStruct.getString("text"));
            Assertions.assertEquals(-6, newStruct.getValue("d"));

            // record (nest)
            Assertions.assertEquals(3, newRecord.getValue("s.a"));
            Assertions.assertEquals(4, newRecord.getValue("s.b"));
            Assertions.assertEquals(5, newRecord.getValue("s.c"));
            Assertions.assertEquals("Hello", newRecord.getString("s.text"));
            Assertions.assertEquals(-6, newRecord.getValue("s.d"));

            // record
            Assertions.assertEquals(3, newRecord.getValue("a"));
            Assertions.assertEquals(-6, newRecord.getValue("d"));
            Assertions.assertEquals("Hello", newRecord.getString("text"));
        }

        @Test
        void ignoringDeletedFields() throws SyntaxException {
            SerialCompiler oldCompiler = new SerialCompiler("""
                    RECORD Sub
                        LET int x = 100
                        LET int y = 200
                    
                    RECORD Main
                        LET int head = 1
                        LET double oldVariable = 2.0
                        NEST Sub redundantSub
                        LET int obsoleteArr[3] = 10 20 30
                        LET int tail = 9
                    """);

            SerialCompiler newCompiler = new SerialCompiler("""
                    RECORD Sub
                        LET int x = 0
                        LET int y = 0
                    
                    RECORD Main
                        LET int head = 0
                        __LET double oldVariable = 2.0
                        __NEST Sub redundantSub
                        __LET int obsoleteArr[3] = 10 20 30
                        LET int tail = 0
                    """);

            Record oldMain = oldCompiler.makeRecord("Main");
            byte[] bytes = oldMain.serialize();
            Record newMain = newCompiler.makeRecord(bytes);

            // Verify active fields are read correctly
            Assertions.assertEquals(1, newMain.getValue("head"));
            Assertions.assertEquals(9, newMain.getValue("tail"));

            // Verify that ignored fields do not exist in the new record's active model
            Assertions.assertNull(newMain.getValue("oldVariable"));
            Assertions.assertNull(newMain.getValue("redundantSub.x"));
            Assertions.assertNull(newMain.getValue("redundantSub.y"));
            Assertions.assertNull(newMain.getValue("obsoleteArr[0]"));
            Assertions.assertNull(newMain.getValue("obsoleteArr[1]"));
            Assertions.assertNull(newMain.getValue("obsoleteArr[2]"));
        }

        @Test
        void expandingArrays() throws SyntaxException {
            SerialCompiler oldCompiler = new SerialCompiler("""
                    STRUCT Sub
                        LET int vals[2] = 10 20
                    
                    RECORD Main
                        NEST Sub items[2]
                        LET int footer = 77
                    """);

            SerialCompiler newCompiler = new SerialCompiler("""
                    STRUCT Sub
                        LET int vals[4] = -1 -2 -3 ...
                        LET int tag = 88
                    
                    RECORD Main
                        NEST Sub items[3]
                        LET int footer = 0
                    """);

            Record oldMain = oldCompiler.makeRecord("Main");
            byte[] bytes = oldMain.serialize();
            Record newMain = newCompiler.makeRecord(bytes);

            // old array part
            for (int i = 0; i < 2; i++) {
                Integer[] vals = newMain.getArray("items[" + i + "].vals", Integer.class);
                Assertions.assertEquals(4, vals.length);
                Assertions.assertEquals(10, vals[0]);
                Assertions.assertEquals(20, vals[1]);
                Assertions.assertEquals(-3, vals[2]);
                Assertions.assertEquals(-3, vals[3]);
            }

            // new array part
            Integer[] vals2 = newMain.getArray("items[2].vals", Integer.class);
            Assertions.assertEquals(4, vals2.length);
            Assertions.assertEquals(-1, vals2[0]);
            Assertions.assertEquals(-2, vals2[1]);
            Assertions.assertEquals(-3, vals2[2]);
            Assertions.assertEquals(-3, vals2[3]);

            // footer check
            Assertions.assertEquals(77, newMain.getValue("footer"));

            // evolution of the Sub structure itself
            Record oldSub = oldCompiler.makeRecord("Sub");
            byte[] subBytes = oldSub.serialize();
            Record newSub = newCompiler.makeRecord(subBytes);

            Integer[] subVals = newSub.getArray("vals", Integer.class);
            Assertions.assertEquals(4, subVals.length);
            Assertions.assertEquals(10, subVals[0]); // old
            Assertions.assertEquals(20, subVals[1]); // old
            Assertions.assertEquals(-3, subVals[2]);
            Assertions.assertEquals(-3, subVals[3]);
            Assertions.assertEquals(88, newSub.getValue("tag"));
        }

        @Test
        void shrinkingArrays() throws SyntaxException {
            SerialCompiler oldCompiler = new SerialCompiler("""
                    RECORD Sub
                        LET int vals[4] = 10 20 30 40
                    
                    RECORD Main
                        NEST Sub items[3]
                        LET int footer = 99
                    """);

            SerialCompiler newCompiler = new SerialCompiler("""
                    RECORD Sub
                        LET int vals[2] = 0 0
                    
                    RECORD Main
                        NEST Sub items[2]
                        LET int footer = 0
                    """);

            Record oldMain = oldCompiler.makeRecord("Main");
            byte[] bytes = oldMain.serialize();
            Record newMain = newCompiler.makeRecord(bytes);

            for (int i = 0; i < 2; i++) {
                Integer[] vals = newMain.getArray("items[" + i + "].vals", Integer.class);

                // check inner dimension shrink: 4 -> 2 (values 30 and 40 should be discarded)
                Assertions.assertEquals(2, vals.length);
                Assertions.assertEquals(10, vals[0]);
                Assertions.assertEquals(20, vals[1]);
            }

            // check pointer alignment (footer should be correctly read after skipped bytes)
            Assertions.assertEquals(99, newMain.getValue("footer"));
        }

        @Test
        void extremeEvolutionTest() throws SyntaxException {
            // This is a pure Gemini test

            // 1. OLD SCHEMA: A bulky version with more data
            SerialCompiler oldCompiler = new SerialCompiler("""
                    STRUCT Data
                        LET int vals[2] = 10 20
                    
                    RECORD Sub
                        LET int id = 500
                        NEST Data info
                    
                    RECORD Main
                        LET int header = 1
                        NEST Sub items[3]
                        LET int obsolete = 999
                        LET int footer = 7
                    """);

            // 2. NEW SCHEMA: Reordered, shrinked, expanded and partially hidden
            SerialCompiler newCompiler = new SerialCompiler("""
                    STRUCT Data
                        LET int vals[4] = 0 0 30 ...
                    
                    RECORD Sub
                        NEST Data info
                        __LET int id = 0
                    
                    RECORD Main
                        __LET int header = 0
                        LET int brandNew = 88
                        NEST Sub items[2]
                        __LET int obsolete = 0
                        LET int footer = 0
                    """);

            Record oldMain = oldCompiler.makeRecord("Main");
            byte[] bytes = oldMain.serialize();
            Record newMain = newCompiler.makeRecord(bytes);

            // --- Verification ---

            // 1. Hidden & New Fields
            Assertions.assertNull(newMain.getValue("header"));       // Was 1, now hidden
            Assertions.assertEquals(88, newMain.getValue("brandNew")); // Brand new default
            Assertions.assertEquals(7, newMain.getValue("footer"));    // Correctly mapped from old tail

            // 2. Array Shrinking (Outer: 3 -> 2)
            // items[2] from old stream should be ignored entirely.

            // 3. Nested Evolution (Sub & Data)
            for (int i = 0; i < 2; i++) {
                String path = "items[" + i + "].";

                // __LET int id was present in old data (500), but should be null now
                Assertions.assertNull(newMain.getValue(path + "id"));

                // Data.vals expanded (2 -> 4)
                Integer[] vals = newMain.getArray(path + "info.vals", Integer.class);
                Assertions.assertEquals(4, vals.length);
                Assertions.assertEquals(10, vals[0]); // From old stream
                Assertions.assertEquals(20, vals[1]); // From old stream
                Assertions.assertEquals(30, vals[2]); // New default
                Assertions.assertEquals(30, vals[3]); // New default (ellipsis)
            }
        }

        private void testRoundTrip(Record record) {
            byte[] bytes = record.serialize();
            Record recordAfterTrip = record.getCompiler().makeRecord(bytes);
            Assertions.assertEquals(record, recordAfterTrip);
        }
    }

    @Nested
    class Compression {
        final int MAX_SIZE = 10 * 1024 * 1024; // 10 MB
        SerialCompiler compiler, nullCompiler;

        @BeforeEach
        void init() throws SyntaxException {
            compiler = new SerialCompiler("""
                    RECORD Tree3
                        LET char text[17]
                        LET int variable
                    
                    RECORD Tree2
                        NEST Tree3 tree[13]
                    
                    RECORD Tree1
                        NEST Tree2 tree[7]
                    
                    RECORD Record
                        LET double dbl = Infinity
                        NEST Tree1 tree[5]
                    """, new SerialCompiler.Compressor() {

                @Override
                public byte[] compress(byte[] data) {
                    if (data == null) return null;
                    try {
                        Deflater deflater = new Deflater();
                        deflater.setInput(data);
                        deflater.finish();

                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
                        byte[] buffer = new byte[1024];
                        while (!deflater.finished()) {
                            int count = deflater.deflate(buffer);
                            outputStream.write(buffer, 0, count);
                        }
                        outputStream.close();
                        return outputStream.toByteArray();
                    } catch (Exception e) {
                        return null;
                    }
                }

                @Override
                public byte[] decompress(byte[] data) {
                    if (data == null) return null;
                    try {
                        Inflater inflater = new Inflater();
                        inflater.setInput(data);

                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int totalSize = 0;

                        while (!inflater.finished()) {
                            int count = inflater.inflate(buffer);
                            totalSize += count;

                            if (totalSize > MAX_SIZE) {
                                return null;
                            }

                            outputStream.write(buffer, 0, count);
                        }
                        return outputStream.toByteArray();
                    } catch (Exception e) {
                        return null;
                    }
                }
            });

            nullCompiler = new SerialCompiler("", new SerialCompiler.Compressor() {
                @Override
                public byte[] compress(byte[] data) {
                    return null;
                }

                @Override
                public byte[] decompress(byte[] data) {
                    return null;
                }
            });
        }

        @Test
        void compressionWorks() {
            Record record = compiler.makeRecord("Record");
            record.setString("tree[1].tree[4].tree[6].text", "Hello World!");

            byte[] bytes = record.serialize();
            Record newRecord = compiler.makeRecord(bytes);

            Assertions.assertEquals("Hello World!", newRecord.getString("tree[1].tree[4].tree[6].text"));
            Assertions.assertEquals(record, newRecord);
        }

        @Test
        void shouldBeImmuneToNullsInCompression() {
            Record record = nullCompiler.makeRecord("__EMPTY_RECORD__");
            byte[] bytes = record.serialize();
            Assertions.assertEquals(0, bytes.length);
        }

        @Test
        void shouldBeImmuneToNullsInDecompression() {
            Record record = nullCompiler.makeRecord(new byte[]{1, 2, 3});
            Assertions.assertEquals("__EMPTY_RECORD__", record.RECORD_TYPE);
        }
    }
}
