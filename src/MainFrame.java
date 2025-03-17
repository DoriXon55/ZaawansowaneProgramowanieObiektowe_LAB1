
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MainFrame {
    private JFrame frame;
    private static final String DIR_PATH = "files";
    private final int statistics;
    private final AtomicBoolean isRunning;
    private final int howMuchproducents;
    private final int howMuchcustomers;
    private final ExecutorService executor;
    private final List<Future<?>> producentFuture;
    private Path referenceFilePath;
    private Map<String, Long> referenceVector;
    private boolean useSimilarityMode = false;
    private JTextArea outputArea;
    private final List<CosineSimilarity.SimilarityResults> similarityResults = new ArrayList<>();

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            Logger.getLogger(MainFrame.class.getName()).log(Level.SEVERE, "Błąd ustawiania look and feel", e);
        }
        EventQueue.invokeLater(() -> {
            try {
                MainFrame window = new MainFrame();
                window.frame.pack();
                window.frame.setAlwaysOnTop(true);
                window.frame.setVisible(true);
            } catch (Exception e) {
                Logger.getLogger(MainFrame.class.getName()).log(Level.SEVERE, "Komunikat opisujący kontekst błędu", e);
            }
        });
    }

    public MainFrame() {
        statistics = 10;
        isRunning = new AtomicBoolean(false);
        howMuchproducents = 1;
        howMuchcustomers = 2;
        executor = Executors.newFixedThreadPool(howMuchcustomers + howMuchproducents);
        producentFuture = new ArrayList<>();
        initialize();
    }

    private void initialize() {
        frame = new JFrame("Analiza plików tekstowych");
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                executor.shutdownNow();
            }
        });
        frame.setBounds(100, 100, 800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel controlPanel = new JPanel();
        frame.getContentPane().add(controlPanel, BorderLayout.NORTH);

        JButton selectReferenceFileButton = new JButton("Wybierz plik referencyjny");
        selectReferenceFileButton.addActionListener(_ -> selectReferenceFile());
        controlPanel.add(selectReferenceFileButton);

        JCheckBox similarityModeCheckBox = getJCheckBox();
        controlPanel.add(similarityModeCheckBox);

        JPanel buttonPanel = new JPanel();
        frame.getContentPane().add(buttonPanel, BorderLayout.SOUTH);

        JButton btnStart = new JButton("Start");
        btnStart.addActionListener(_ -> {
            similarityResults.clear();
            getMultiThreadedStatistics();
        });
        buttonPanel.add(btnStart);



        outputArea = new JTextArea(15, 50);
        outputArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(outputArea);
        frame.getContentPane().add(scrollPane, BorderLayout.CENTER);
        outputArea.append("Rozpoczynanie analizy plików tekstowych...\n");

        JButton btnStop = new JButton("Stop");
        btnStop.addActionListener(_ -> {
            isRunning.set(true);
            for (Future<?> future : producentFuture) {
                future.cancel(true);
            }
        });
        buttonPanel.add(btnStop);

        JButton btnShowResults = new JButton("Pokaż wyniki podobieństwa");
        btnShowResults.addActionListener(_ -> displaySortedResults());
        buttonPanel.add(btnShowResults);

        JButton btnClose = new JButton("Zamknij");
        btnClose.addActionListener(_ -> {
            executor.shutdownNow();
            frame.dispose();
        });
        buttonPanel.add(btnClose);
    }

    private JCheckBox getJCheckBox() {
        JCheckBox similarityModeCheckBox = new JCheckBox("Tryb podobieństwa");
        similarityModeCheckBox.addActionListener(_ -> {
            useSimilarityMode = similarityModeCheckBox.isSelected();
            if (useSimilarityMode && referenceFilePath == null) {
                similarityModeCheckBox.setSelected(false);
                useSimilarityMode = false;
                JOptionPane.showMessageDialog(frame, "Najpierw wybierz plik referencyjny!", "OSTRZEŻENIE!", JOptionPane.WARNING_MESSAGE);
            }
        });
        return similarityModeCheckBox;
    }


    private void displaySortedResults() {
        outputArea.append("Liczba wyników podobieństwa: " + similarityResults.size() + "\n");
        if (similarityResults.isEmpty()) {
            outputArea.append("Brak wyników podobieństwa do wyświetlenia.\n");
            return;
        }

        // Sortuje wyniki od największego do najmniejszego
        Collections.sort(similarityResults);

        outputArea.append("\n===== POSORTOWANE WYNIKI PODOBIEŃSTWA =====\n");
        for (CosineSimilarity.SimilarityResults result : similarityResults) {
            outputArea.append(result.toString() + "\n");
        }
        outputArea.append("=======================================\n");
    }

    private void selectReferenceFile() {
        JFileChooser fileChooser = new JFileChooser(DIR_PATH);
        fileChooser.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".txt");
            }

            @Override
            public String getDescription() {
                return "Pliki tekstowe (*.txt)";
            }
        });
        int result = fileChooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            referenceFilePath = selectedFile.toPath();
            outputArea.setText("Wybrano plik referencyjny: " + selectedFile.getName() + "\n");

            // Wczytaj wektor referencyjny od razu po wyborze pliku
            try {
                referenceVector = getLinkedCountedWord(referenceFilePath, 0);
                outputArea.append("Wczytano wektor referencyjny z " + selectedFile.getName() + "\n");
            } catch (Exception e) {
                outputArea.append("Błąd wczytywania pliku referencyjnego: " + e.getMessage() + "\n");
            }
        }
    }



    private void getMultiThreadedStatistics() {
        for (Future<?> f : producentFuture) {
            if (!f.isDone()) {
                JOptionPane.showMessageDialog(frame, "Nie można uruchomić nowego zadania! Przynajmniej jeden producent nadal działa!", "OSTRZEŻENIE!", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        if (useSimilarityMode && referenceVector == null) {
            try {
                referenceVector = getLinkedCountedWord(referenceFilePath, 0);
                outputArea.append("Wczytano wektor referencyjny z pliku: " +
                        referenceFilePath.getFileName() + "\n");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame,
                        "Błąd wczytywania pliku referencyjnego: " + e.getMessage(),
                        "Błąd", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        isRunning.set(false);
        producentFuture.clear();
        similarityResults.clear();

        final BlockingQueue<Optional<Path>> queue = new LinkedBlockingQueue<>(howMuchcustomers);
        Runnable producent = getRunnable(queue);

        Runnable consument = () -> {
            final String name = Thread.currentThread().getName();
            final String startInfo = String.format("Konsument: %s uruchomiony...\n", name);
            SwingUtilities.invokeLater(() -> outputArea.append(startInfo));

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Optional<Path> optionalPath = queue.take();
                    if (optionalPath.isEmpty()) {
                        break;
                    }

                    Path path = optionalPath.get();
                    final String fileName = path.getFileName().toString();
                    final String processInfo = String.format("Przetwarzanie pliku: %s\n", fileName);
                    SwingUtilities.invokeLater(() -> outputArea.append(processInfo));

                    try {
                        // Zawsze oblicz statystykę słów
                        Map<String, Long> wordStats = getLinkedCountedWord(path, statistics);

                        // Wyświetl statystykę wyrazów
                        SwingUtilities.invokeLater(() -> {
                            outputArea.append("\nStatystyka wyrazów w pliku " + fileName + ":\n");
                            if (wordStats.isEmpty()) {
                                outputArea.append("  Brak słów w pliku lub wszystkie zostały odfiltrowane\n");
                            } else {
                                wordStats.forEach((word, count) ->
                                        outputArea.append("  " + word + " = " + count + "\n")
                                );
                            }
                        });

                        // Jeśli tryb podobieństwa jest włączony, oblicz podobieństwo kosinusowe
                        if (useSimilarityMode && referenceVector != null) {
                            // Do porównania używamy pełnego wektora słów (bez limitu)
                            Map<String, Long> fullWordStats = getLinkedCountedWord(path, 0);

                            // Dodaj wynik do listy wyników podobieństwa
                            final double finalSimilarity = CosineSimilarity.cosineSimilarity(referenceVector, fullWordStats);
                            CosineSimilarity.SimilarityResults newResult =
                                    new CosineSimilarity.SimilarityResults(fileName, finalSimilarity);

                            synchronized (similarityResults) {
                                similarityResults.add(newResult);
                            }

                            // Wyświetl wynik podobieństwa
                            SwingUtilities.invokeLater(() -> outputArea.append("  Podobieństwo do pliku referencyjnego: " +
                                    String.format("%.4f", finalSimilarity) + "\n"));
                        }
                    } catch (Exception e) {
                        final String errorMessage = e.getMessage();
                        SwingUtilities.invokeLater(() -> outputArea.append("Błąd podczas przetwarzania pliku " + fileName + ": " +
                                errorMessage + "\n"));
                        Logger.getLogger(MainFrame.class.getName()).log(Level.SEVERE, "Komunikat opisujący kontekst błędu", e);
                    }
                } catch (InterruptedException e) {
                    final String interruptInfo = String.format("Oczekiwanie konsumenta %s zostało przerwane!\n", name);
                    SwingUtilities.invokeLater(() -> outputArea.append(interruptInfo));
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            final String endInfo = String.format("Konsument %s zakończył pracę!\n", name);
            SwingUtilities.invokeLater(() -> outputArea.append(endInfo));
        };

        // Uruchom producentów
        for (int i = 0; i < howMuchproducents; i++) {
            Future<?> f = executor.submit(producent);
            producentFuture.add(f);
        }

        // Uruchom konsumentów
        for (int i = 0; i < howMuchcustomers; i++) {
            executor.submit(consument);
        }
    }

    private Runnable getRunnable(BlockingQueue<Optional<Path>> queue) {
        final int delay = 60;

        // Wstaw poison pills dla wszystkich konsumentów
        // Wyszukiwanie plików tekstowych i dodawanie ich do kolejki
        return () -> {
            final String name = Thread.currentThread().getName();
            final String info = String.format("Producent: %s uruchomiony...\n", name);
            SwingUtilities.invokeLater(() -> outputArea.append(info));

            while (!Thread.currentThread().isInterrupted()) {
                if (isRunning.get()) {
                    // Wstaw poison pills dla wszystkich konsumentów
                    for (int i = 0; i < howMuchcustomers; i++) {
                        try {
                            queue.put(Optional.empty());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            final String interruptInfo = String.format("Producent: %s przerwany podczas wysyłania poison pills\n", name);
                            SwingUtilities.invokeLater(() -> outputArea.append(interruptInfo));
                        }
                    }
                    break;
                } else {
                    // Wyszukiwanie plików tekstowych i dodawanie ich do kolejki
                    try {
                        Path dir = Paths.get(DIR_PATH);
                        if (Files.exists(dir) && Files.isDirectory(dir)) {
                            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                                private final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**/*.txt");

                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                    if (useSimilarityMode && file.equals(referenceFilePath)) {
                                        return FileVisitResult.CONTINUE;
                                    }

                                    if (matcher.matches(file) && !Thread.currentThread().isInterrupted() && !isRunning.get()) {
                                        try {
                                            Optional<Path> optPath = Optional.of(file);
                                            queue.put(optPath);
                                            final String fileInfo = "Dodano plik do analizy: " + file.getFileName() + "\n";
                                            SwingUtilities.invokeLater(() -> outputArea.append(fileInfo));
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            return FileVisitResult.TERMINATE;
                                        }
                                    }
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        } else {
                            final String dirError = "Katalog " + DIR_PATH + " nie istnieje lub nie jest katalogiem!\n";
                            SwingUtilities.invokeLater(() -> outputArea.append(dirError));
                        }
                    } catch (IOException e) {
                        final String ioError = "Błąd wejścia/wyjścia: " + e.getMessage() + "\n";
                        SwingUtilities.invokeLater(() -> outputArea.append(ioError));
                    }
                }

                final String delayInfo = String.format("Producent: %s ponownie sprawdzi katalogi za %d sekund...\n", name, delay);
                SwingUtilities.invokeLater(() -> outputArea.append(delayInfo));

                try {
                    TimeUnit.SECONDS.sleep(delay);
                } catch (InterruptedException e) {
                    final String interruptInfo = String.format("Przerwa producenta %s przerwana!\n", name);
                    SwingUtilities.invokeLater(() -> outputArea.append(interruptInfo));
                    if (!isRunning.get()) Thread.currentThread().interrupt();
                }
            }

            final String endInfo = String.format("Producent %s zakończył pracę!\n", name);
            SwingUtilities.invokeLater(() -> outputArea.append(endInfo));
        };
    }

    private Map<String, Long> getLinkedCountedWord(Path path, int wordsLimit) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            Map<String, Long> wordCount = reader.lines()
                    .flatMap(line -> Arrays.stream(line.toLowerCase()
                            .replaceAll("[^a-ząćęłńóśźż0-9\\s]", " ")
                            .trim()
                            .split("\\s+")))
                    .filter(word -> !word.isEmpty())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

            if (wordsLimit > 0) {
                return wordCount.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(wordsLimit)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (k, _) -> { throw new IllegalStateException(String.format("Błąd! Duplikat klucza %s", k)); },
                                LinkedHashMap::new
                        ));
            } else {
                return wordCount;
            }
        }
    }
}
