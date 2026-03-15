# Serial

This is a data serialization library for Java. Its one-file architecture allows for a really simple importing process. 
Write code in a custom data-defining language and use this library to serialize and deserialize records. You can also validate and repair data automatically during deserialization or by using the `checkAndRepair()` method.

### How To Import?
Importing is very simple. Just copy the file `SerialCompiler.java` into the package `page.se3.serial` inside your Java project. You can optionally include `SerialCompilerTests.java` (unit tests written in the JUnit5 framework). 
Files can be obtained from [here](https:/Kamiloso/Serial/releases).

### Usage Example

```java
package page.se3.myproject;

import page.se3.serial.SerialCompiler;

public class Main {

    // Global object that represents a compiled SRL code
    // It is immutable after creation, so it can be safely used from
    // different threads.
    private static SerialCompiler serial;

    private static void initializeSerial() {
        try {
            // 1. Initialize the compiler (without custom compressor for this example)
            serial = new SerialCompiler("""
                    RECORD User
                        LET char username[16]
                        LET int age = 18
                        ENSURE username MATCHES "^[\\\\p{L}0-9_-]*$"
                        ENSURE age >= 0 AND <= 120
                    """);

        } catch (SerialCompiler.SyntaxException e) {
            System.err.println("An error occurred (syntax): " + e.getMessage());
            e.printStackTrace();
        }
    }

    // An example record that uses Serial-Java-Lib to serialize
    // and deserialize objects.
    private record User(String username, int age) {
        private static final String RECORD_TYPE = "User";

        public byte[] serialize() {
            return serial.makeRecord(RECORD_TYPE)
                    .setString("username", username)
                    .setValue("age", age)
                    .serialize();
        }

        public static User deserialize(byte[] bytes) {
            var record = serial.makeRecord(bytes);
            if (!RECORD_TYPE.equals(record.RECORD_TYPE)) { // checks whether deserialization was unsuccessful
                record = serial.makeRecord(RECORD_TYPE);
            }

            return new User(
                    record.getString("username"),
                    record.getValue("age", Integer.class)
            );
        }
    }

    public static void main(String[] args) {
        initializeSerial();

        try {
            // Create the user record
            User user = new User("Cameleon-123", 35);
            System.out.println("Original user: " + user);

            // Serialize record
            byte[] bytes = user.serialize();
            System.out.println("Serialized to " + bytes.length + " bytes.");

            // Deserialize record
            User user2 = User.deserialize(bytes);
            System.out.println("User from bytes: " + user2);

        } catch (SerialCompiler.OperationException e) {
            System.err.println("An error occurred (operation): " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

---

## Table of Contents

| Section | Description | Quick Links |
| :--- | :--- | :--- |
| **[1. Overview & Compatibility](#1-overview--compatibility)** | Introduction, features, and binary schema evolution. | |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ [1.1 Library Functionalities](#11-overview-of-library-functionalities) | Basic concepts, SRL, and Java examples. | |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ [1.2 Binary Format Compatibility](#12-binary-format-compatibility) | `STRUCT` vs `RECORD` differences. | [Structures](#121-structures) · [Records](#122-records) · [Schema Evolution](#123-schema-evolution-support) |
| **[2. Structure of the SRL Language](#2-structure-of-the-srl-language)** | Syntax, parser behavior, and available commands. | |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ [2.1 Parser Operation](#21-parser-operation) | Processing steps and core logic. | [Comments](#211-removing-comments) · [Tokenization](#212-tokenization) · [Patterns](#213-construction-of-patterns-for-data-structures) · [Verification](#214-verification-of-the-obtained-serialcompiler-object) |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ [2.2 Commands](#22-commands) | Language keywords and their usage. | [`RECORD`](#221-record--struct) · [`DEF`](#222-define--def) · [`LET`](#223-let) · [`SET`](#224-set) · [`NEST`](#225-nest) · [`BASE`](#226-base) · [`ENSURE`](#227-ensure) |
| **[3. Public API](#3-public-api)** | Java library classes, methods, and integrations. | |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ [3.1 Public Classes & Interfaces](#31-public-classes-and-interfaces) | Core API components and data structures. | [`SerialCompiler`](#311-serialcompiler) · [`Compressor`](#312-serialcompilercompressor) · [`Record`](#313-serialcompilerrecord) |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ [3.2 Exceptions](#32-exceptions) | Error handling and library validation. | [`SyntaxException`](#321-serialcompilersyntaxexception) · [`OperationException`](#322-serialcompileroperationexception) |

---
---

# 1. Overview & Compatibility

## 1.1 Overview of Library Functionalities

The Serial library allows for defining fixed-size data structures in the SRL language, consisting of primitive types found in the Java language. It supports nesting structures / records and creating inheritance hierarchies. There is support for fixed-size arrays, including UTF-16 encoded strings, represented by character arrays padded with NULL characters at the end. 

Data structures defined this way can be modified, serialized to a byte array, and deserialized using the library's public interface.

**🔹 SRL Code Example:**
```srl
STRUCT Vector3
    LET double x ENSURE x IS FINITE
    LET double y ENSURE y IS FINITE
    LET double z ENSURE z IS FINITE

RECORD Entity
    LET long id
    NEST Vector3 position

RECORD Player : Entity
    SET position.y = 5.0
    LET char nickname [16] = "Player"
    ENSURE nickname MATCHES "^[a-zA-Z0-9_-]+$"
```

**🔹 Usage Example in Java:**
```java
SerialCompiler compiler = new SerialCompiler(srlSourceCode);
Record player = compiler.makeRecord("Player");

player.setValue("id", 5L);
player.setString("nickname", "Wojtek2005");

byte[] bytes = player.serialize();
Record deserialized = compiler.makeRecord(bytes);

System.out.println(deserialized.getReport());
```

---

## 1.2 Binary Format Compatibility

One of the biggest advantages of the Serial library is the ability to maintain binary format compatibility in case of data structure schema evolution. It differs significantly between structures (`STRUCT`) and records (`RECORD`). Renaming a structure to a record and vice versa does not preserve binary format compatibility.

### 1.2.1 Structures
Their binary format is simple. Fields are serialized exactly in the order they were defined. This makes schema evolution possibilities very limited for them. However, their advantage is their small size and fast serialization / deserialization.

### 1.2.2 Records
Fields are serialized together with a header, which is a 3-byte hash of their name. This hash is based on the CRC32 function, removing the most significant byte from its output. Thanks to this procedure, records are much more resistant to changes in the data structure, which, however, entails a slightly larger size.

### 1.2.3 Schema Evolution Support

| Type of change | Structure | Record |
| :--- | :--- | :--- |
| **Change field order** | NO | YES |
| **Add field** | Only at the end | YES |
| **Remove field** | NO | Through deprecating comment |
| **Change array size** | Only enlarging primitive array at the end | YES |
| **Add inheritance / nesting** | Only adding `BASE` / `NEST` command at the end | YES |
| **Remove inheritance / nesting** | NO | Through deprecating comment |
| **Change field type** | NO | NO |

> **💡 Note:** If a record inherits from a structure or nests it, all record fields originating from the structure will be treated during serialization as part of the record. The same applies to record fields passing to a structure.

---
---

# 2. Structure of the SRL Language

## 2.1 Parser Operation

The parser processes the SRL source code in sequential steps, described below.

### 2.1.1 Removing Comments
Before parsing begins, the program removes all comments from the provided SRL code. There are 3 types of comments:

* **Single-line comments:** They start with the `//` sequence. The parser ignores all characters from this point until the occurrence of a newline character.
* **Multi-line (block) comments:** Bounded by `/*` (start) and `*/` (end) tags. Everything between them is skipped, regardless of the number of lines.
* **Deprecating comments:** A special type of comment used to mark fields removed from records. Each line starting with the `_` character will be considered a deprecated line. Before the actual compilation, the parser always performs a test compilation, in which it does not remove deprecated lines but only removes the leading `_` characters from them. The test compilation must succeed; otherwise, the main one will end with an error. During the main compilation, deprecated lines are completely ignored. This system aims to eliminate the risk of field hash collisions in case of an attempt to add a new field with a hash that already existed in the record / structure but was later removed.

**🔹 Example of code with comments:**
```srl
/*
This is a long
comment
*/

// This is a short comment

RECORD Employee
    LET int id
    LET char name [32]
    LET char surname [32]
    _LET float old_field_1
    __LET float old_field_2
```

### 2.1.2 Tokenization
In the tokenization step, each line of the SRL source code is processed into an ordered set of tokens. Whitespaces are treated as separators, and empty tokens are skipped. Additionally, the use of quotes, e.g., `"my text"`, `'my text'`, communicates to the parser that the entire text inside, including the quotes, is to be treated as a single token. The parser supports escaping quotes using the `\` character, e.g., `"my text \"quote\""`, while the escape character itself is not removed at this parsing stage.

**🔹 Example & Tokenization Result:**
```srl
LET char title [64] = "Adam Mickiewicz - \"Pan Tadeusz\""
```
The tokenization process of the analyzed line results in generating the following sequence of tokens:

| Index | Token Value |
| :--- | :--- |
| `0` | `LET` |
| `1` | `char` |
| `2` | `title[64]` |
| `3` | `=` |
| `4` | `"Adam Mickiewicz - \"Pan Tadeusz\""` |

The parser identifies keywords in the analyzed line: `RECORD`, `STRUCT`, `DEFINE`, `DEF`, `LET`, `SET`, `NEST`, `BASE`, and `ENSURE`. The detection of one of the listed tokens constitutes a point of dividing the instruction into individual commands.

**🔹 Example of Command Division:**

| Index | Token Value |
| :--- | :--- |
| `0` | `LET` |
| `1` | `char` |
| `2` | `title[64]` |
| `3` | `=` |
| `4` | `"Adam Mickiewicz - \"Pan Tadeusz\""` |
| `5` | `ENSURE` |
| `6` | `title` |
| `7` | `NOT` |
| `8` | `MATCHES` |
| `9` | `"Juliusz Słowacki"` |

**Resulting split into commands:**
```srl
LET char title [64] = "Adam Mickiewicz - \"Pan Tadeusz\""
ENSURE title NOT MATCHES "Juliusz Słowacki"
```

### 2.1.3 Construction of Patterns for Data Structures
All commands obtained in the previous step are executed sequentially during compilation, resulting in the creation of special `RecordInfo` objects representing patterns for data structures defined in the SRL language. They contain a list of all variables located in the record / structure and their default values. They are used to construct `Record` objects of a specific type.

### 2.1.4 Verification of the Obtained SerialCompiler Object
In the last step, a verification takes place to check if all obtained `RecordInfo` objects are valid. For this purpose, the following steps are performed:

1.  **Creation** of a list of all defined data structures (`Record` objects), filled with default values.
2.  **Verification** whether all `Record` objects meet the assumptions defined using the `ENSURE` command.
3.  **Serialization and deserialization** of each `Record` object to ensure it proceeds correctly.

> **⚠️ Important:** In case of failure of any of the above points, the compilation process is aborted with an error.

---

## 2.2 Commands

Below are all the commands occurring in the SRL language.

### 2.2.1 RECORD / STRUCT

The `RECORD` and `STRUCT` commands are used to notify the parser that the definition of a new record / structure is starting, depending on the chosen keyword. Optionally, inheritance information can be placed in them, without the need to use the `BASE` command.

**🔹 Syntax**
```srl
STRUCT / RECORD name
STRUCT / RECORD name : base_1 base_2 .. base_n
```

**🔹 Example**
```srl
STRUCT Base1
    LET int a = 1

RECORD Base2
    LET int b = 2

RECORD Derived : Base1 Base2
    LET int c = 3
```

> **💡 Note:** The `Derived` record inherits all fields along with default values from the `Base1` structure and the `Base2` record, and then adds its own fields to them.

### 2.2.2 DEFINE / DEF

The `DEFINE` and `DEF` commands act as text macros, replacing the indicated tokens directly before their interpretation. They differ primarily in scope: `DEFINE` is global, while `DEF` is a local macro whose validity expires with the closing of the current record or structure. 

> **⚠️ Important:** In the case of names sounding the same, the local `DEF` macro always takes precedence over the global `DEFINE`. It should be noted that the definition instructions themselves are not subject to the token replacement mechanism.

**🔹 Syntax**
```srl
DEFINE / DEF search replace
DEFINE / DEF search = replace
```

**🔹 Example**
```srl
// it is recommended to use the '$' sign before constants
DEFINE $global-value = 100

RECORD Record1
    DEF $local-value = 50
    LET int ar1 = $local-value

RECORD Record2
    // here $local-value no longer exists
    LET int ar2 = $global-value
```

### 2.2.3 LET

The `LET` command is used to define variables and arrays inside a record / structure and to assign default values to them. If the default value is not specified, it will be filled with zeros.

#### 📌 Defining variables
Variables in the SRL language can take the form of all 8 primitive types found in the Java language. Built-in parsing functions, such as `Short.parseShort(...)`, were used to parse them. The exception are the `boolean` and `char` types, which received dedicated parsers.

**Syntax & Example:**
```srl
// LET primitive_type name [= default_value]
RECORD Record
    LET byte bitwise = -5
    LET short short_num = -10
    LET int number = 15
    LET long long_num = 20
    LET float fraction1 = 3.5
    LET double fraction2 = 3.3552
    LET char character = 'A' // you can also use %65 , '\u0041' (Unicode) or a single letter A
    LET boolean logic = true // you can also use 0 or 1; false, true expressions are case-insensitive
```

#### 📌 Defining arrays
Arrays are actually `N` repeated declarations of a given variable, where `N` is the size of the array. By writing: `LET int array[3]`, we are actually declaring variables: `array[0]`, `array[1]`, and `array[2]`. 

> **💡 Optimization Tip:** It is recommended to use arrays instead of suffixed variables, because it allows the serializer some optimizations in the size of the binary format, and it is also more convenient for a human.

**Syntax & Example:**
```srl
// LET primitive_type name[size] [= v1 v2 .. vn ...]
RECORD Record
    // Empty array, filled with zeros
    LET int arrayA[5]

    // Array containing consecutive elements from 1 to 3, the rest filled with zeros
    LET int arrayB[5] = 1 2 3

    // Array containing consecutive elements from 1 to 3, the rest filled with the last element (3)
    LET int arrayC[5] = 1 2 3 ...

    // Character array equivalent to the string "ABCD"
    LET char charArray[16] = A B C D
```

#### 📌 Defining strings
Arrays of type `char` can, depending on the context, also be interpreted by the library as strings. Therefore, syntax has been added to the parser allowing the default value of a string to be defined in a visually friendly way.

**Syntax & Example:**
```srl
// LET char name[size] = "Some string\n"
RECORD Record
    // The following declarations are identical to each other.
    LET char text1[16] = "ALA HAS A CAT\n"
    LET char text2[16] = A L A ' ' H A S ' ' A ' ' C A T '\n'
```

### 2.2.4 SET

The `SET` command allows overwriting previously defined default values of variables and arrays. This is particularly useful when nesting and inheriting records, which is mentioned more in further sections of the documentation.

**🔹 Syntax & Example**
```srl
// SET field_name [= default_value]
RECORD Base
    LET int varA = 5
    LET int varB = 5
    LET char text[16] = "ab cd"

RECORD Derived : Base
    SET varA // 0 will be set
    SET varB = 3
    SET text = a b // new string: "ab"
```

### 2.2.5 NEST

The `NEST` command allows for nesting records. This works by copying all variables and assertions defined using the `ENSURE` command to the parent record, preceding each variable name with the nest name, using a dot as a separator. 

Variables pasted into the record using the `NEST` command can be freely modified with the `SET` command by referring to them through the nest name and the dot operator.

**🔹 Syntax & Example**
```srl
// NEST record_name nest_name
// NEST record_name nest_array_name[size]

RECORD Base
    LET int var = 5
    LET char text[16] = a b c d

RECORD Derived
    NEST Base nest
    SET nest.var = 3
    SET nest.text = "abc"

// nest arrays are possible
    NEST Base nests[3]
    SET nests[1].var = 99
```

### 2.2.6 BASE

The `BASE` command allows for inheriting records. This works by copying all variables and assertions defined using the `ENSURE` command to the parent record. Variables pasted into the record using the `BASE` command can be freely modified with the `SET` command. 

> **ℹ️ Info:** Alternatively, instead of the `BASE` command, you can use inheritance inside the `RECORD` / `STRUCT` command.

**🔹 Syntax & Example**
```srl
// BASE record_name
RECORD Base
    LET int var = 5
    LET char text[16] = a b c

RECORD Derived
    BASE Base
    SET var = 3
    SET text = "abc"
```

### 2.2.7 ENSURE

The `ENSURE` command provides an advanced tool for determining when a structure / record is valid. Rules can be defined for variables, arrays, and strings. 

The Public API provides a method for verifying record integrity, which, upon detecting an inconsistency, immediately fixes it (restores the field to its default value). Integrity verification can also happen implicitly during deserialization. However, it is possible to call the deserialization method in such a way that verification does not occur. Default field values must satisfy all `ENSURE` rules; otherwise, compilation will end with an error.

Conditions are written in **DNF (Disjunctive Normal Form)**, using `OR` and `AND` operators without using parentheses. Each elementary condition must consist of at least two tokens: an operator and an argument. Adding the `NOT` token before a condition negates it.

**🔹 Command Syntax**
```srl
ENSURE field_name [conditions_in_DNF]

// Elementary condition syntax
operator argument
NOT operator argument
```

#### 📌 Operator Types

**1. Classical Operators**
These are `>`, `>=`, `<`, `<=`, `==`, and `!=`. They act on variables and represent comparisons of the field with the argument. If an array is provided instead of a field, these operators apply to each of its elements separately. These comparisons for floating-point numbers work according to the **IEEE 754** standard.
```srl
RECORD Record
    LET double a = -1
    LET double arr[100] = 3 ...
    ENSURE a > 0 AND < 10 OR == -1
    ENSURE arr != 5 // for each element of the arr array
```

**2. IS Operator**
Using this operator only makes sense for floating-point numbers (`float` and `double`). For any other value, it returns `false`. It accepts 3 options as an argument: `FINITE`, `INFINITE`, and `NAN`, which check whether a floating-point number belongs to one of these three groups.
```srl
RECORD Record
    LET double a = 5
    LET double b = -Infinity
    LET double c = NaN
    LET double d = 0
    ENSURE a IS FINITE
    ENSURE b IS INFINITE
    ENSURE c IS NAN
    ENSURE d NOT IS NAN
```

**3. String Operators**
These operators check the correctness of a string and treat it as an integral whole. Includes `EQUALS` (checks for exact equality) and `MATCHES` (checks against a regular expression regex).
```srl
RECORD Record
    LET char name[64]
    ENSURE name NOT EQUALS "Adolf"
    ENSURE name MATCHES "^[\\p{L}-]*$"
```

#### 📌 Evaluation Rules
* **Order:** Conditions are checked according to the *short-circuit evaluation* rule, which means that when the value of an expression becomes certain, the evaluation is immediately interrupted.
* **Compilation:** Conditions are not compiled as long as they do not need to be used. An invalid condition might throw an error at compilation during verification of default records (`SyntaxException`), or only appear when checking an actual record (`OperationException`).

---
---

# 3. Public API

The Serial library provides a set of public classes and methods through which it can be used. Below is the documentation of the public interface.

## 3.1 Public Classes and Interfaces

Utility classes are discussed below.

---

### 3.1.1 SerialCompiler

This is a class whose object represents a parsed SRL language file. It acts as a factory for nested `SerialCompiler.Record` objects.

#### 🛠 Constructors

```java
SerialCompiler(String code, Compressor compressor)
```
> Compiles code based on the SRL source code and creates an object factory with the specified binary data compression algorithm.

* **Parameters:**
  * `code` (`String`) – Code in SRL format.
  * `compressor` (`Compressor`) – An object implementing the `SerialCompiler.Compressor` interface.
* **Exceptions:**
  * `SyntaxException` – Thrown when the code syntax is invalid.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
SerialCompiler(String code)
```
> Constructor overload disabling binary data compression.

<br>

#### 🆕 Creating Records

```java
Record makeRecord(String recordType)
```
> Creates a new, default instance of the `Record` object based on the given record / structure type.

* **Parameters:**
  * `recordType` (`String`) – The identification name of the record / structure defined in the SRL language.
* **Returns:** * `Record` – A `Record` class object of the given type, filled with default values defined in the SRL language.
* **Exceptions:**
  * `OperationException` – Thrown when the given record type is invalid.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
Record makeRecord(byte[] bytes, boolean autoFix = true)
```
> Creates a new instance of the `Record` object based on binary data, previously passing it through the decompression algorithm specified in the constructor.

* **Parameters:**
  * `bytes` (`byte[]`) – An array of bytes representing binary data for deserialization.
  * `autoFix` (`boolean`) – Should the record be fixed in case of a mismatch in `ENSURE` rules?
* **Returns:** * `Record` – A deserialized `Record` class object with the type read from the binary format or `"__EMPTY_RECORD__"` if the format is invalid.
* **Exceptions:**
  * `OperationException` – Thrown when the given record type is invalid.
  * `NullPointerException` – Thrown when any parameter is `null`.

---

### 3.1.2 SerialCompiler.Compressor

This is an interface representing the compression / decompression algorithm used during serialization / deserialization.

#### ⚙️ Interface Methods

```java
byte[] compress(byte[] data)
```
> Compresses the passed binary data according to the implemented algorithm.

* **Parameters:**
  * `data` (`byte[]`) – An array of bytes containing raw data to be compressed.
* **Returns:** * `byte[]` – A new array of bytes containing the compressed data.

<br>

```java
byte[] decompress(byte[] data)
```
> Decompresses the passed binary data, restoring it to its original form before compression.

* **Parameters:**
  * `data` (`byte[]`) – An array of bytes containing compressed data.
* **Returns:** * `byte[]` – A new array of bytes containing the original, unpacked data or `null` in case of problems (e.g., detecting a decompression bomb).

---

### 3.1.3 SerialCompiler.Record

This is an object representing a structure / record (depending on the definition in SRL), which acts as a wrapper for a dictionary, providing the methods presented below. Variables are stored in it under keys in a flattened manner (e.g., `variable`, `array[5]`, `nests[2].variable`). This key or its prefix is called a **path**.

> **`String RECORD_TYPE`** *(final field)*
> Stores the record type defined in the SRL language.

<br>

#### 📝 Managing Variables

```java
Record setValue(String path, Object value)
```
> Overwrites a single variable in the record with the given value.

* **Parameters:**
  * `path` (`String`) – The key whose value will be modified (e.g., `nested[2].variable`).
  * `value` (`Object`) – The new value of the variable, cast to the `Object` class.
* **Returns:** * `Record` – The current `Record` class object.
* **Exceptions:**
  * `OperationException` – Thrown when the path to the variable is invalid or the value is of an incorrect type.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
T getValue(String path, Class clazz)
```
> Retrieves a single variable from the specified path and casts it to the given class.

* **Parameters:**
  * `path` (`String`) – The key whose value will be retrieved.
  * `clazz` (`Class`) – The target type class to be retrieved (e.g., `Boolean.class`).
* **Returns:** * `T` – The value located under the specified path cast to type `T`, or `null` if the value does not exist or is not of the identical type to the `clazz` class.
* **Exceptions:**
  * `OperationException` – Thrown when `clazz` is a primitive.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
Object getValue(String path)
```
> Retrieves a single variable from the specified path without casting.

* **Parameters:**
  * `path` (`String`) – The key whose value will be retrieved.
* **Returns:** * `Object` – The value located under the specified path or `null` if the value does not exist.
* **Exceptions:**
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

#### 📊 Managing Arrays

```java
Record setArray(String path, Object[] array)
```
> Inserts an array of primitive types at the given path.

* **Parameters:**
  * `path` (`String`) – The path where the array is located (e.g., `array`, when the key values are `array[0]`, `array[1]`, etc.).
  * `array` (`Object[]`) – An array of wrappers for the primitive type (e.g., `Float[]`) with which the array is to be filled.
* **Returns:** * `Record` – The current `Record` class object.
* **Exceptions:**
  * `OperationException` – Thrown when the array sizes are incompatible or the values are of an incorrect type.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
T[] getArray(String path, Class clazz)
```
> Retrieves an array of primitive types from the given path.

* **Parameters:**
  * `path` (`String`) – The path where the array is located.
  * `clazz` (`Class`) – The target type class to be retrieved (e.g., `Boolean.class` for a `Boolean[]` array).
* **Returns:** * `T[]` – The largest possible array of the given type that can be obtained by retrieving values from subsequent keys.
* **Exceptions:**
  * `OperationException` – Thrown when `clazz` is a primitive.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

#### 🔤 Managing Strings

```java
Record setString(String path, String str)
```
> Places a string inside a `Character` array at the given path, filling the unused space with `NULL` characters.

* **Parameters:**
  * `path` (`String`) – The path to the character array (e.g., `text`, when the key values are `text[0]`, `text[1]`, etc.).
  * `str` (`String`) – The string to be inserted into the array.
* **Returns:** * `Record` – The current `Record` class object.
* **Exceptions:**
  * `OperationException` – Thrown when the string is too long to fit in the array.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
String getString(String path, boolean forceUnicode = true)
```
> Converts a `Character` array at the given path into a string, removing `NULL` characters from its end.

* **Parameters:**
  * `path` (`String`) – The path to the character array.
  * `forceUnicode` (`boolean`) – Should invalid Unicode sequences be replaced with the replacement character with hex code = FFFD?
* **Returns:** * `String` – The string obtained from the given path to the character array, or an empty string if such an array does not exist.
* **Exceptions:**
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

#### 📂 Managing Records

```java
Record setRecord(String path, Record record)
```
> Pastes every variable of the given record into the specified path in the current record. Ignores variables for which the pasting would fail.

* **Parameters:**
  * `path` (`String`) – The path to which the record should be pasted (e.g., `nest` when we want to paste its variables with the `nest.` prefix, or an empty string to paste them without a prefix).
  * `record` (`Record`) – The `Record` class object that will be pasted into the current object.
* **Returns:** * `Record` – The current `Record` class object.
* **Exceptions:**
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
Record getRecord(String path, String recordType)
```
> Copies every variable starting with the appropriate prefix from the current record to a new record of the given type, removing this prefix. Ignores variables for which the pasting would fail.

* **Parameters:**
  * `path` (`String`) – The path from which the record should be copied (e.g., `nest` when we want to copy variables with the `nest.` prefix, or an empty string to copy them without a prefix).
  * `recordType` (`String`) – The type of the record to which the values are to be copied.
* **Returns:** * `Record` – A new `Record` class object of the `recordType` type, into which the variables were copied.
* **Exceptions:**
  * `OperationException` – Thrown when the target type does not exist.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

#### 🔄 Record Conversions

```java
Record castTo(String recordType)
```
> Copies all variables from the current record to a new `Record` class object of the given type. Ignores variables for which the pasting would fail.

* **Parameters:**
  * `recordType` (`String`) – The type of the record to which the values are to be copied.
* **Returns:** * `Record` – A new `Record` class object of the `recordType` type, into which the variables were copied.
* **Exceptions:**
  * `OperationException` – Thrown when the target type does not exist.
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
Record clone()
```
> Performs a deep copy of the record, and then returns it.

<br>

#### 🗑 Resetting Variables / Fields

```java
Record resetValue(String path)
```
> Restores a single variable to its default value defined in the SRL code.

* **Parameters:**
  * `path` (`String`) – The key whose value will be reset (e.g., `nested[2].variable`).
* **Returns:** * `Record` – The current `Record` class object.
* **Exceptions:**
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
Record resetArray(String path)
```
> Restores an array of primitives to its default value defined in the SRL code.

* **Parameters:**
  * `path` (`String`) – The path where the array is located.
* **Returns:** * `Record` – The current `Record` class object.
* **Exceptions:**
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

```java
Record resetTree(String path)
```
> Restores default values for the variable indicated by `path` and for its entire subordinate data hierarchy. The main `path` key and all keys starting with structural prefixes: `path.` and `path[` are reset.

* **Parameters:**
  * `path` (`String`) – The path to the data hierarchy that is to be reset.
* **Returns:** * `Record` – The current `Record` class object.
* **Exceptions:**
  * `NullPointerException` – Thrown when any parameter is `null`.

<br>

#### ⚡ Other Methods

```java
byte[] serialize()
```
> Serializes the record / structure to a binary form, and then compresses the result using the algorithm provided in the `SerialCompiler` class constructor.

* **Returns:** * `byte[]` – A new byte array representing the binary form of the record / structure.

<br>

```java
boolean checkAndRepair()
```
> Checks all `ENSURE` conditions, and if it finds inconsistencies, restores the field that the given conditions apply to back to its default state.

* **Returns:** * `boolean` – A boolean value determining whether any repair was made.
* **Exceptions:**
  * `OperationException` – Thrown when an error occurs while checking `ENSURE` conditions (e.g., invalid regex).

<br>

```java
SerialCompiler getCompiler()
```
> Returns the `SerialCompiler` class object with which the current record was created.

<br>

```java
LinkedHashMap<String, Class> getOrderedVariables()
```
> Returns a new `LinkedHashMap` object mapping all paths to the types of values located in them. The fields are ordered according to the order of their declaration in the SRL code.

<br>

```java
String getReport()
```
> Returns a human-readable report of the record's contents. The fields are ordered according to the order of their declaration in the SRL code.

<br>

#### 📦 Object Class Methods

```java
String toString()
```
> Returns a textual representation of the object, containing information about the class and the record type in the format `"SerialCompiler.Record::RECORD_TYPE"`.

<br>

```java
boolean equals(Object other)
```
> Compares the current record with another object for logical equality.

* **Parameters:**
  * `other` (`Object`) – The object with which the current record is to be compared.
* **Returns:** * `boolean` – `true` if both objects belong to the `Record` class, originate from the same compiler, have the same type, and identical content of all variables; `false` otherwise.

<br>

```java
int hashCode()
```
> Generates a hash code based on the reference to the parent `SerialCompiler` class object, the record type, and the values of all variables, taking into account their declaration order.

---

## 3.2 Exceptions

The implemented exception classes are discussed below. All special exceptions thrown in the library possess an error message that can be read using the `getMessage()` method.

### 3.2.1 SerialCompiler.SyntaxException

This is an exception inheriting from `Exception`, thrown in the `SerialCompiler` class constructor when an SRL language syntax error or another unexpected error occurs.

```java
Integer getLine()
```
> Returns the line on which the error occurred in the SRL code, or `null` if it is impossible to determine.

<br>

### 3.2.2 SerialCompiler.OperationException

This is an exception inheriting from `RuntimeException`, thrown when an unexpected error occurs in the library that is unrelated to the compilation of the SRL code. This exception does not have a public interface other than the one available within the `RuntimeException` class.
