import kotlinx.cli.*
import kotlinx.serialization.json.*
import java.io.File

fun main(args: Array<String>) {
    val parser = ArgParser("localiser")

    val listCommonKeys by parser.option(
        ArgType.Boolean,
        fullName = "listCommonKeys",
        shortName = "c",
        description = "List common keys across all locales"
    ).default(false)

    val listUniqueKeys by parser.option(
        ArgType.Boolean,
        fullName = "listUniqueKeys",
        shortName = "u",
        description = "List unique keys in each locale file"
    ).default(false)

    val printJsObjects by parser.option(
        ArgType.Boolean,
        fullName = "printLocales",
        shortName = "p",
        description = "Print JS objects for common keys"
    ).default(false)

    val maxKeyLength by parser.option(
        ArgType.Int,
        fullName = "keyLength",
        shortName = "l",
        description = "Maximum length for keys (0 for no limit)"
    ).default(0)

    val updateProject by parser.option(
        ArgType.Boolean,
        fullName = "updateProject",
        shortName = "push",
        description = "Update project's locale files"
    ).default(false)

    parser.parse(args)

    val localeFiles = File(".").listFiles { file -> file.extension == "json" }
    println("Checking Directory ${File(".").absolutePath}")

    if (localeFiles.isNullOrEmpty()) {
        println("No locale files found in the current directory.")
        return
    }

    val locales = localeFiles.associate { file ->
        file.nameWithoutExtension to Json.parseToJsonElement(file.readText()).jsonObject
    }

    if (listCommonKeys) {
        listCommonKeys(locales, maxKeyLength)
    }

    if (listUniqueKeys) {
        listUniqueKeys(locales, maxKeyLength)
    }

    if (printJsObjects) {
        printJsObjects(locales, maxKeyLength)
    }

    if (updateProject) {
        updateProjectLocales(locales, maxKeyLength)
    }
}

fun listCommonKeys(locales: Map<String, JsonObject>, maxKeyLength: Int) {
    println("Common keys across all locales:")
    val commonKeys = locales.values.map { it.keys }.reduce { acc, keys -> acc.intersect(keys) }
    println("Found ${commonKeys.size} common keys")
    commonKeys.forEach { key -> println("  ${sanitizeKey(key, maxKeyLength)}") }
}

fun listUniqueKeys(locales: Map<String, JsonObject>, maxKeyLength: Int) {
    println("\nUnique keys in each locale:")
    locales.forEach { (locale, json) ->
        val uniqueKeys = json.keys - locales.filter { it.key != locale }.flatMap { it.value.keys }.toSet()
        if (uniqueKeys.isNotEmpty()) {
            println(" ${getLocaleFullName(locale)} | ${uniqueKeys.size} :")
            uniqueKeys.forEach { key -> println("  ${sanitizeKey(key, maxKeyLength)}") }
        }
    }
}

fun printJsObjects(locales: Map<String, JsonObject>, maxKeyLength: Int) {
    val commonKeys = locales.values.map { it.keys }.reduce { acc, keys -> acc.intersect(keys) }
    println("\nJS objects for ${commonKeys.size} common keys:")

    locales.forEach { (locale, json) ->
        println("$locale: {")
        commonKeys.forEach { key ->
            val sanitizedKey = sanitizeKey(key, maxKeyLength)
            val value = json[key]?.jsonPrimitive?.content ?: ""
            val processedValue = processValue(value)
            println("  $sanitizedKey: \"$processedValue\",")
        }
        println("}")
        println()
    }
}

fun sanitizeKey(key: String, maxKeyLength: Int): String {
    val result = StringBuilder()

    for (char in key) {
        if (char.isLetterOrDigit()) {
            result.append(char.lowercaseChar())
        } else if (char == ' ') {
            result.append('_')
        } else if (char == '_') {
            result.append(char)
        }
        // All other special characters are ignored
    }

    if (maxKeyLength > 0 && result.length > maxKeyLength)
        result.setLength(maxKeyLength)

    return result.toString()
}

fun processValue(value: String): String {
    // Check if the value contains dynamic string placeholders
    if (value.contains("%") || value.contains("$")) {
        // Replace %n$X placeholders
        var processed = value.replace(Regex("%([0-9]+)\\$([a-zA-Z])")) { matchResult ->
            val number = matchResult.groupValues[1].toInt() - 1
            "{$number}"
        }

        // Replace $X placeholders
        processed = processed.replace(Regex("\\$([a-zA-Z])"), "{0}")

        return processed
    }

    // If no dynamic placeholders, return the original value unchanged
    return value
}

fun updateProjectLocales(locales: Map<String, JsonObject>, maxKeyLength: Int) {
    println("Enter the path to your project's locales folder:")
    val localesPath = readlnOrNull() ?: return
    val localesDir = File(localesPath)

    if (!localesDir.exists() || !localesDir.isDirectory) {
        println("Invalid directory path. Please ensure the directory exists.")
        return
    }
    val commonTranslations = locales.values.map { it.keys }.reduce { acc, keys -> acc.intersect(keys) }
    locales.forEach { (locale, translations) ->
        val localeFile = File(localesDir, "$locale.js")
        if (!localeFile.exists()) {
            println("Warning: $locale.js does not exist in the specified directory. Skipping.")
            return@forEach
        }

        val content = localeFile.readText()
        val updatedContent = updateLocaleFile(content, locale, commonTranslations, translations, maxKeyLength)
        localeFile.writeText(updatedContent)
        println("✅Updated $locale.js")
    }
    println("${commonTranslations.size} common strings pushed successfully!")
}

fun updateLocaleFile(
    content: String,
    locale: String,
    commonKeys: Set<String>,
    translations: JsonObject,
    maxKeyLength: Int
): String {
    val lastBrace = content.lastIndexOf('}')
    if (lastBrace == -1) {
        // If no closing brace found, append the translations at the end
        return "$content\n\n${formatTranslations(locale, commonKeys, translations, maxKeyLength)}\n"
    }

    // Check if there's a nested object closure
    val nestedClosureIndex = content.lastIndexOf("}};")
    return if (nestedClosureIndex != -1 && nestedClosureIndex > lastBrace - 5) {
        // Insert before the nested closure
        content.substring(0, nestedClosureIndex) +
                formatTranslations(locale, commonKeys, translations, maxKeyLength) + ",\n" +
                content.substring(nestedClosureIndex)
    } else {
        // Insert before the last closing brace
        var subStringBeforeLastClosingBrace = content.substring(0, lastBrace)
        with(subStringBeforeLastClosingBrace) {
            if (!endsWith(",\n"))
                subStringBeforeLastClosingBrace = replaceRange(lastIndex, lastIndex, ",")
        }
        subStringBeforeLastClosingBrace +
                formatTranslations(locale, commonKeys, translations, maxKeyLength) + ",\n" +
                content.substring(lastBrace)
    }
}

fun formatTranslations(locale: String, commonKeys: Set<String>, translations: JsonObject, maxKeyLength: Int): String {
    val commonTranslations = translations.filter { commonKeys.contains(it.key) }
    return commonTranslations.entries.joinToString(
        separator = ",\n",
        prefix = "    $locale: {\n",
        postfix = "\n    }",
        transform = { (key, value) ->
            val sanitizedKey = sanitizeKey(key, maxKeyLength)
            val content = value.jsonPrimitive.content
            val processedContent = processValue(content)

            "        $sanitizedKey: \"$processedContent\""
        }
    )
}

val localeFullNames = mapOf(
    "en" to "English",
    "hi" to "Hindi",
    "kn" to "Kannada",
    "ta" to "Tamil",
    "te" to "Telugu",
    "mr" to "Marathi",
    "bn" to "Bengali"
)

fun getLocaleFullName(sign: String): String {
    return localeFullNames.getOrDefault(sign, "Unknown")
}