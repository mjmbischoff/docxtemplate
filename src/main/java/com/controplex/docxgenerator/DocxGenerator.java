package com.controplex.docxgenerator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.reflectoring.docxstamper.DocxStamper;
import io.reflectoring.docxstamper.DocxStamperConfiguration;
import org.docx4j.Docx4jProperties;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.SpreadsheetMLPackage;
import org.docx4j.openpackaging.parts.SpreadsheetML.SharedStrings;
import org.docx4j.openpackaging.parts.SpreadsheetML.WorkbookPart;
import org.docx4j.openpackaging.parts.SpreadsheetML.WorksheetPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xlsx4j.exceptions.Xlsx4jException;
import org.xlsx4j.org.apache.poi.ss.usermodel.DataFormatter;
import org.xlsx4j.sml.Sheet;
import org.xlsx4j.sml.Worksheet;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;

@Command(name = "docx-generator", mixinStandardHelpOptions = true, version = "docx-generator 1.0",
        description = "Generates docx files based on an xlsx for data and a template in docx")
public class DocxGenerator implements Callable<Integer> {
    private final static Logger logger = LoggerFactory.getLogger(DocxGenerator.class);

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DocxGenerator()).execute(args);
        System.exit(exitCode);
    }

    @Option(names = {"-t", "--template-file"}, required = true, description = "template file")
    private Path template;
    @Option(names = {"-d", "--data-file"}, required = true, description = "data file")
    private File dataFile;
    @Option(names = {"-r", "--replace"}, defaultValue = "false", negatable = true, description = "replace output files if exists (default: ${DEFAULT-VALUE})")
    private boolean replaceOutput;
    @Option(names = {"-1", "--first-row-only"}, defaultValue = "false", negatable = true, description = "Only process one document (default: ${DEFAULT-VALUE})")
    private boolean firstRowOnly;
    @Option(names = {"-s", "--sheet"}, description = "name of the sheet to use (default: first sheet of workbook)")
    private String sheetName = null;
    @Option(names = {"-c", "--entity-column"}, required = true, description = "entity colum (name), used to name the output file")
    private String entityColumn = "ParticipantId";
    @Option(names = {"-o", "--output-dir" }, defaultValue = "/tmp/output", description = "dir to output the files (default: ${DEFAULT-VALUE})")
    private Path outputDir;

    @Option(names = { "-v", "--verbose" },
            description = """
                Verbose mode. Helpful for troubleshooting.
                Multiple -v options increase the verbosity.
                """)
    private boolean[] verbose = new boolean[0];

    @Override
    public Integer call() throws RuntimeException {
        updateLogging(verbose);
        configureDocx4j();

        logger.info("Generating docx files. Data file = {}, Template = {}, Output Directory = {}", dataFile, template, outputDir);

        List<Row> rows = readRowsFromDataFile(dataFile, sheetName);

        for(Row row : rows) {
            if(row.isBlank()) {
                logger.debug("Skipping row because it's empty. ");
                continue;
            }
            String outputname = row.column(entityColumn);
            if(outputname==null || outputname.isBlank()) {
                logger.warn("Skipping row as column '{}' is empty. - {} ", entityColumn, row);
                continue;
            }
            createOutputDir(outputDir);
            Path output = outputDir.resolve(  outputname + ".docx");
            if(replaceOutput) {
                removeExistingFile(output);
            } else {
                if(Files.exists(output)) {
                    logger.info("File {} exists.. skipping.", output);
                    continue;
                }
            }
            generateDocForRow(row, template, output);
            if(firstRowOnly) break;
        }
        return 0;
    }

    private void configureDocx4j() {
        Docx4jProperties.setProperty("javax.xml.parsers.SAXParserFactory", "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl");
        Docx4jProperties.setProperty("javax.xml.parsers.DocumentBuilderFactory", "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");
        Docx4jProperties.setProperty("docx4j.jaxb.preprocess.always", "MainDocumentPart,StyleDefinitionsPart,FooterPart");
    }

    private static void updateLogging(boolean[] verbose) {
        if(logger instanceof ch.qos.logback.classic.Logger) {
            LoggerContext context = ((ch.qos.logback.classic.Logger) logger).getLoggerContext();
            context.getLogger("org.docx4j").setLevel(Level.ERROR);
            context.getLogger("com.controplex.docxgenerator").setLevel(Level.WARN);
            context.getLogger("org.xlsx4j").setLevel(Level.ERROR);
            int verbosity = 0;
            for(boolean entry : verbose) {
                if(entry) verbosity++;
                else verbosity--;
            }

            if(verbosity>=1) {
                context.getLogger("com.controplex.docxgenerator").setLevel(Level.INFO);
            }
            if(verbosity>=2) {
                context.getLogger("com.controplex.docxgenerator").setLevel(Level.DEBUG);
            }
            if(verbosity>=3) {
                context.getLogger("org.docx4j").setLevel(Level.INFO);
            }
            if(verbosity>=4) {
                context.getLogger("org.docx4j").setLevel(Level.DEBUG);
                context.getLogger("org.xlsx4j").setLevel(Level.DEBUG);
            }
        }
    }

    private static void createOutputDir(Path outputDir) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void generateDocForRow(Row row, Path templateLocation, Path outputLocation) {
        logger.info("Generating output file '{}'. {} ", outputLocation, row);
        try (InputStream template = Files.newInputStream(templateLocation, READ)) {
            try (OutputStream out = Files.newOutputStream(outputLocation, CREATE_NEW)) {
                DocxStamper<Row> stamper = new DocxStamper<>(new DocxStamperConfiguration());
                stamper.stamp(template, row, out);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Row> readRowsFromDataFile(File dataFile, String sheetname) {
        List<Row> rows = new ArrayList<>();

        try {
            SpreadsheetMLPackage spreadsheetMLPackage = SpreadsheetMLPackage.load(dataFile);
            SharedStrings sharedStrings = spreadsheetMLPackage.getWorkbookPart().getSharedStrings();
            Worksheet selectedSheet = pickSelectedSheet(spreadsheetMLPackage, sheetname);
            DataFormatter dataFormatter = new DataFormatter();
            List<org.xlsx4j.sml.Row> sourceRows = selectedSheet.getSheetData().getRow();

            int headerRowNumber = 0;
            Map<String, String> columnNames = new HashMap<>();
            org.xlsx4j.sml.Row headerRow = sourceRows.get(headerRowNumber);
            headerRow.getC().forEach(cell -> columnNames.put(extractColumnName(cell.getR()), dataFormatter.formatCellValue(cell)));

            int startRowNumber = 1;
            for(int rowNumber = startRowNumber; rowNumber<sourceRows.size(); rowNumber++) {
                org.xlsx4j.sml.Row sourceRow = sourceRows.get(rowNumber);
                Row row = new Row();
                sourceRow.getC().forEach(cell -> row.setColumn(columnNames.get(extractColumnName(cell.getR())), dataFormatter.formatCellValue(cell)));
                rows.add(row);
            }
        } catch (Docx4JException | Xlsx4jException e) {
            throw new RuntimeException(e);
        }

        return rows;
    }

    private static String extractColumnName(String r) {
        StringBuilder builder = new StringBuilder();
        for(char c : r.toCharArray()) {
            if(Character.isAlphabetic(c)) builder.append(c);
            else break;
        }
        return builder.toString();
    }

    private static void removeExistingFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Worksheet pickSelectedSheet(SpreadsheetMLPackage spreadsheetMLPackage, String sheetname) throws Xlsx4jException, Docx4JException {
        if(sheetname == null || sheetname.isBlank()) {
            return spreadsheetMLPackage.getWorkbookPart().getWorksheet(0).getContents();
        } else {
            return findsheetByName(spreadsheetMLPackage, sheetname);
        }
    }

    private static Worksheet findsheetByName(SpreadsheetMLPackage spreadsheetMLPackage, String sheetname) throws Docx4JException {
        WorkbookPart worksheetPart = spreadsheetMLPackage.getWorkbookPart();
        List<Sheet> sheets = worksheetPart.getContents().getSheets().getSheet();
        Optional<Sheet> sheet = sheets.stream().filter(s -> sheetname.equalsIgnoreCase(s.getName())).findFirst();
        if(sheet.isPresent()) {
            return ((WorksheetPart) worksheetPart.getRelationshipsPart().getPart(sheet.get().getId())).getContents();
        } else {
            return null;
        }
    }
}