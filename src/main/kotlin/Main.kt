import kotlinx.cli.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

fun main(args: Array<String>) {
    val parser = ArgParser("localiser")

    val printVersion by parser.option(
        ArgType.Boolean,
        fullName = "version",
        shortName = "v",
        description = "Print localiser version"
    ).default(false)

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

    val localeFiles = File(".")
        .listFiles { file ->
            supportedLocales.contains(file.nameWithoutExtension) && file.extension == "json"
        }
    println("Checking Directory ${File(".").absolutePath}")

    if (localeFiles.isNullOrEmpty()) {
        println("No locale files found in the current directory.")
        return
    }
    val locales = localeFiles.associate { file ->
        var jsonFileContent = file.readText()
        // Regex to find all single backslashes followed by specific escape sequences like \u2028 or \n
        val singleBackslashRegex = Regex("""(\\[uU][0-9a-fA-F]{4}|\\n)""")
        // Replace each single backslash with a double backslash
        jsonFileContent = singleBackslashRegex.replace(jsonFileContent) { matchResult ->
            "\\" + matchResult.value
        }
        file.nameWithoutExtension to Json.parseToJsonElement(jsonFileContent).jsonObject
    }

    if (printVersion) {
        println("localiser version: v1.0.0")
        return
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
    if (!printVersion && !listCommonKeys && !listUniqueKeys && !printJsObjects && !updateProject) {
        println("Run 'localiser --help to learn about available commands")
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
    var anyUniqueKeysFound = false
    locales.forEach { (locale, json) ->
        val uniqueKeys = json.keys - locales.filter { it.key != locale }.flatMap { it.value.keys }.toSet()
        if (uniqueKeys.isNotEmpty()) {
            println(" ${getLocaleFullName(locale)} | ${uniqueKeys.size} :")
            uniqueKeys.forEach { key -> println("  ${sanitizeKey(key, maxKeyLength)}") }
            anyUniqueKeysFound = true
        }
    }
    if (anyUniqueKeysFound.not()) {
        println("No unique keys found in any locale files!")
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
    val trimmedKey = key.trimStart { it.isLetter().not() }

    for (char in trimmedKey) {
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
        processed = processed.replace(Regex("%([a-zA-Z])"), "{0}")

        return processed
    }

    // If no dynamic placeholders, return the original value unchanged
    return value
}

fun updateProjectLocales(locales: Map<String, JsonObject>, maxKeyLength: Int) {
    var projectPath = getProjectPath()

    if (projectPath == null) {
        println("Enter the path to your project's locales directory:")
        val newProjectPath = readlnOrNull() ?: ""
        val localesDir = File(newProjectPath)

        if (!localesDir.exists() || !localesDir.isDirectory) {
            println("Invalid directory path. Please ensure the directory exists.")
            return
        }

        projectPath = newProjectPath
        saveConfig(key = "projectPath", value = newProjectPath)
    }

    val commonTranslations = locales.values.map { it.keys }.reduce { acc, keys -> acc.intersect(keys) }
    if (commonTranslations.isEmpty()) {
        println("No common translations found. Skipping update!")
        return
    }

    println("Enter the object name:")
    val objectName = readlnOrNull() ?: "object"

    locales.forEach { (locale, translations) ->
        val localeFile = File(projectPath, "$locale.js")
        if (!localeFile.exists()) {
            println("Warning: $locale.js does not exist in the specified directory. Skipping.")
            return@forEach
        }

        val content = localeFile.readText()
        val updatedContent = updateLocaleFile(content, commonTranslations, translations, maxKeyLength, objectName)
        localeFile.writeText(updatedContent)
        println("✅Updated $locale.js")
    }

    // Update constants.js file
    val constantsFile = File(projectPath, "constants.js")
    if (!constantsFile.exists()) {
        println("Warning: constants.js does not exist in the specified directory. Creating a new file.")
        constantsFile.createNewFile()
    }

    val constantsContent = constantsFile.readText()
    val updatedConstantsContent = updateConstantsFile(constantsContent, commonTranslations, maxKeyLength, objectName)
    constantsFile.writeText(updatedConstantsContent)
    println("✅Updated constants.js")

    println("${commonTranslations.size} common strings pushed successfully!")
}

fun getProjectPath(): String? {
    val config = loadConfig()
    val savedPath = config.getProperty("projectPath")

    if (savedPath != null) {
        println("Using saved project path: $savedPath")
        println("Do you want to use this path? (Y/n)")
        val response = readlnOrNull()?.lowercase() ?: "y"
        if (response == "y" || response.isEmpty()) {
            return savedPath
        }
    }
    return null
}

fun saveConfig(key: String, value: String) {
    val config = loadConfig()
    config.setProperty(key, value)
    val configFile = getConfigFile()
    FileOutputStream(configFile).use { config.store(it, "Localiser Configuration") }
}

fun loadConfig(): Properties {
    val config = Properties()
    val configFile = getConfigFile()
    if (configFile.exists()) {
        FileInputStream(configFile).use { config.load(it) }
    }
    return config
}

fun getConfigFile(): File {
    val userHome = System.getProperty("user.home")
    return File(userHome, ".localiser.properties")
}

fun updateLocaleFile(
    content: String,
    commonKeys: Set<String>,
    translations: JsonObject,
    maxKeyLength: Int,
    objectName: String
): String {
    val lastBrace = content.lastIndexOf('}')
    if (lastBrace == -1) {
        // If no closing brace found, append the translations at the end
        return "$content\n\n${formatTranslations(commonKeys, translations, maxKeyLength, objectName)}\n"
    }

    // Check if there's a nested object closure
    val nestedClosureIndex = content.lastIndexOf("}};")
    return if (nestedClosureIndex != -1 && nestedClosureIndex > lastBrace - 5) {
        // Insert before the nested closure
        content.substring(0, nestedClosureIndex) +
                formatTranslations(commonKeys, translations, maxKeyLength, objectName) + ",\n" +
                content.substring(nestedClosureIndex)
    } else {
        // Insert before the last closing brace
        var subStringBeforeLastClosingBrace = content.substring(0, lastBrace)
        with(subStringBeforeLastClosingBrace) {
            if (!endsWith(",\n"))
                subStringBeforeLastClosingBrace = replaceRange(lastIndex, lastIndex, ",")
        }
        subStringBeforeLastClosingBrace +
                formatTranslations(commonKeys, translations, maxKeyLength, objectName) + ",\n" +
                content.substring(lastBrace)
    }
}

fun formatTranslations(
    commonKeys: Set<String>,
    translations: JsonObject,
    maxKeyLength: Int,
    objectName: String
): String {
    val commonTranslations = translations.filter { commonKeys.contains(it.key) }
    return commonTranslations.entries.joinToString(
        separator = ",\n",
        prefix = "    $objectName: {\n",
        postfix = "\n    }",
        transform = { (key, value) ->
            val sanitizedKey = sanitizeKey(key, maxKeyLength)
            val content = value.jsonPrimitive.content
            val processedContent = processValue(content)
            val quotedContent = if (isComplexString(processedContent)) {
                "`$processedContent`"
            } else {
                "\"$processedContent\""
            }

            "        $sanitizedKey: $quotedContent"
        }
    )
}

fun isComplexString(s: String): Boolean {
    val complexPattern = Regex("[\n'\\\\<]|\\\\u")
    return complexPattern.containsMatchIn(s)
}

fun updateConstantsFile(
    content: String,
    commonTranslations: Set<String>,
    maxKeyLength: Int,
    objectName: String
): String {
    val lastBrace = content.lastIndexOf('}')
    val newMappings = commonTranslations.joinToString(",\n") { key ->
        val sanitizedKey = sanitizeKey(key, maxKeyLength)
        "    ${sanitizedKey.uppercase()}: \"$objectName.$sanitizedKey\""
    }

    return if (lastBrace == -1) {
        // If no closing brace found, create a new object
        "{\n$newMappings\n}"
    } else {
        // Insert before the last closing brace
        var subStringBeforeLastClosingBrace = content.substring(0, lastBrace)
        with(subStringBeforeLastClosingBrace) {
            if (!endsWith(",\n"))
                subStringBeforeLastClosingBrace = replaceRange(lastIndex, lastIndex, ",")
        }
        subStringBeforeLastClosingBrace + "\n$newMappings\n" + content.substring(lastBrace)
    }
}

val supportedLocales = listOf(
    "en",
    "hi",
    "kn",
    "ta",
    "te",
    "mr",
    "bn"
)

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