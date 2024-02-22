package org.jabref.gui.externalfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jabref.gui.externalfiletype.ExternalFileType;
import org.jabref.gui.externalfiletype.ExternalFileTypes;
import org.jabref.gui.externalfiletype.UnknownExternalFileType;
import org.jabref.gui.undo.NamedCompound;
import org.jabref.gui.undo.UndoableFieldChange;
import org.jabref.gui.util.DefaultTaskExecutor;
import org.jabref.logic.bibtex.FileFieldWriter;
import org.jabref.logic.util.io.AutoLinkPreferences;
import org.jabref.logic.util.io.FileFinder;
import org.jabref.logic.util.io.FileFinders;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.StandardField;
import org.jabref.preferences.FilePreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoSetFileLinksUtil {

    public static class LinkFilesResult {
        private final List<BibEntry> changedEntries = new ArrayList<>();
        private final List<IOException> fileExceptions = new ArrayList<>();

        protected void addBibEntry(BibEntry bibEntry) {
            changedEntries.add(bibEntry);
        }

        protected void addFileException(IOException exception) {
            fileExceptions.add(exception);
        }

        public List<BibEntry> getChangedEntries() {
            return changedEntries;
        }

        public List<IOException> getFileExceptions() {
            return fileExceptions;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoSetFileLinksUtil.class);
    private final List<Path> directories;
    private final AutoLinkPreferences autoLinkPreferences;
    private final FilePreferences filePreferences;

    public AutoSetFileLinksUtil(BibDatabaseContext databaseContext, FilePreferences filePreferences, AutoLinkPreferences autoLinkPreferences) {
        this(databaseContext.getFileDirectories(filePreferences), filePreferences, autoLinkPreferences);
    }

    private AutoSetFileLinksUtil(List<Path> directories, FilePreferences filePreferences, AutoLinkPreferences autoLinkPreferences) {
        this.directories = directories;
        this.autoLinkPreferences = autoLinkPreferences;
        this.filePreferences = filePreferences;
    }

    public LinkFilesResult linkAssociatedFiles(List<BibEntry> entries, NamedCompound ce) {
        LinkFilesResult result = new LinkFilesResult();

        for (BibEntry entry : entries) {
            List<LinkedFile> linkedFiles = new ArrayList<>();

            try {
                linkedFiles = findAssociatedNotLinkedFiles(entry);
            } catch (IOException e) {
                result.addFileException(e);
                LOGGER.error("Problem finding files", e);
            }

            if (ce != null) {
                boolean changed = false;

                for (LinkedFile linkedFile : linkedFiles) {
                    // store undo information
                    String newVal = FileFieldWriter.getStringRepresentation(linkedFile);
                    String oldVal = entry.getField(StandardField.FILE).orElse(null);
                    System.out.println("old value==========>"+oldVal);
                    System.out.println("new value==========>"+newVal);
                    //field values to be discussed
                    UndoableFieldChange fieldChange = new UndoableFieldChange(entry, StandardField.FILE, oldVal, newVal);
                    ce.addEdit(fieldChange);
                    changed = true;

                    DefaultTaskExecutor.runInJavaFXThread(() -> {
                        entry.addFile(linkedFile);
                    });
                }

                if (changed) {
                    result.addBibEntry(entry);
                }
            }
        }
        return result;
    }

public List<LinkedFile> findAssociatedNotLinkedFiles(BibEntry entry) throws IOException {
    List<LinkedFile> linkedFiles = new ArrayList<>();
    System.out.println(entry.getFiles()+" ===========files");
    List<String> bibFile = entry.getFiles().stream().map(obj -> obj.getLink()).collect(Collectors.toList());
    System.out.println(bibFile.get(0)+ "=================zero index files");
    List<String> extensions = filePreferences.getExternalFileTypes().stream()
            .map(ExternalFileType::getExtension)
            .collect(Collectors.toList());

    // Run the search operation
    FileFinder fileFinder = FileFinders.constructFromConfiguration(autoLinkPreferences);
    List<Path> result = new ArrayList<>();

    // Search in each directory and its subdirectories
    for (Path directory : directories) {
        List<Path> filesInDirectory = Files.walk(directory)
                .filter(Files::isRegularFile)
//                .filter(path -> bibFile.contains(path.toString()))
                .collect(Collectors.toList());
        System.out.println("files--------->"+filesInDirectory);
//        result.addAll(fileFinder.findAssociatedFiles(entry, filesInDirectory, extensions));
        for (Path file : filesInDirectory) {
            String fileNameDir = file.getFileName().toString();
            System.out.println(fileNameDir+" dir filename");
            for (String bibFilePath : bibFile) {
                Path bibFileNamePath = Paths.get(bibFilePath).getFileName();
                System.out.println(bibFileNamePath + "bibFileNamePath");
                System.out.println("bib file: " + bibFilePath);
                if (bibFileNamePath != null && bibFileNamePath.toString().equals(fileNameDir)) {
                    result.add(file);

                }
            }
        }
        System.out.println(result);
    }

    // Collect the found files that are not yet linked
    for (Path foundFile : result) {
        System.out.println("foundfile--------->"+foundFile);
        boolean fileAlreadyLinked = entry.getFiles().stream()
                .map(file -> file.findIn(directories))
                .anyMatch(file -> {
                    try {

                        return file.isPresent() && Files.isSameFile(file.get(), foundFile);
                    } catch (IOException e) {
                        LOGGER.error("Problem with isSameFile", e);
                    }
                    return false;
                });

        if (!fileAlreadyLinked) {
            Optional<ExternalFileType> type = FileUtil.getFileExtension(foundFile)
                    .map(extension -> ExternalFileTypes.getExternalFileTypeByExt(extension, filePreferences))
                    .orElse(Optional.of(new UnknownExternalFileType("")));

            String strType = type.isPresent() ? type.get().getName() : "";
            Path relativeFilePath = FileUtil.relativize(foundFile, directories);
            LinkedFile linkedFile = new LinkedFile("", relativeFilePath, strType);
            linkedFiles.add(linkedFile);
        }
    }

    return linkedFiles;
}



}
