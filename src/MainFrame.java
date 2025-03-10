import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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
    private boolean useSimilarityMode = false;
    private JTextArea outputArea;



    public static void main(String[] args) {
        try{
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                try{
                    MainFrame window = new MainFrame();
                    window.frame.pack();
                    window.frame.setAlwaysOnTop(true);
                    window.frame.setVisible(true);
                } catch (Exception e)
                {
                    e.printStackTrace();
                }
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
        frame = new JFrame();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                executor.shutdownNow();
            }
        });
        frame.setBounds(100, 100, 450, 300);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel panel = new JPanel();
        frame.getContentPane().add(panel, BorderLayout.NORTH);
        JButton btnStop = new JButton("Stop");
        btnStop.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                isRunning.set(true);
                for (Future<?> future : producentFuture) {
                    future.cancel(true);
                }
            }
        });


        JButton btnStart = new JButton("Start");
        btnStart.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                getMultiThreadedStatistics();
            }
        });




        JButton btnClose = new JButton("Close");
        btnClose.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                executor.shutdownNow();
                frame.dispose();
            }
        });
        panel.add(btnStart);
        panel.add(btnStop);
        panel.add(btnClose);

    }

    private void getMultiThreadedStatistics() {
        for(Future<?> f : producentFuture) {
            if(!f.isDone()) {
                JOptionPane.showMessageDialog(frame, "Nie można uruchomić nowego zadania! Przynajmniej jeden producent nadal działa!", "OSTRZEŻENIE!", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        File directory = new File(DIR_PATH);



        isRunning.set(false);
        producentFuture.clear();
        final BlockingQueue<Optional<Path>> queue = new LinkedBlockingQueue<>(howMuchproducents);
        final int delay  = 60;

        Runnable producent = () -> {
          final String name = Thread.currentThread().getName();
          String info = String.format("Producent: %s ...", name);
          System.out.println(info);


          while(!Thread.currentThread().isInterrupted()) {
              if(isRunning.get()) {
                  // TODO przekazanie poison pills

                  for (int i = 0; i < howMuchcustomers; i++) {
                      try {
                          queue.put(Optional.empty());
                      } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          info = String.format("Producent: %s ... przerwany podczas wysyłania poison pills", name);
                          System.out.println(info);
                      }
                  }
                  break;
              } else {

                  try{
                  Path dir = Paths.get(DIR_PATH);
                  if(Files.exists(dir) && Files.isDirectory(dir)) {
                      Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                          private final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**/*.txt");

                          @Override
                          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                              if (matcher.matches(file) && !Thread.currentThread().isInterrupted() && !isRunning.get()) {
                                  try {
                                      Optional<Path> optPath = Optional.ofNullable((file));
                                      queue.put(optPath);
                                  } catch (InterruptedException e) {
                                      Thread.currentThread().interrupt();
                                  }
                              }


                              return FileVisitResult.CONTINUE;
                          }

                      });
                  }
                  } catch (IOException e) {
                      e.printStackTrace();
                  }




              }
              info = String.format("Producent: %s ponownie sprawdzi katalogi za %d sekund...", name, delay);
              System.out.println(info);
              try{
                  TimeUnit.SECONDS.sleep(delay);
              } catch (InterruptedException e) {
                  info = String.format("Przerwa producenta %s przerwana!", name);
                  System.out.println(info);
                  if (!isRunning.get()) Thread.currentThread().interrupt();
              }
          }
          info = String.format("Producent %s zakończył pracę!", name);
          System.out.println(info);
        };


        Runnable consument = () ->{
          final String name = Thread.currentThread().getName();
          String info = String.format("Konsument: %s uruchomiony...", name);
          System.out.println(info);

          while(!Thread.currentThread().isInterrupted()) {
              try{
                  // TODO pobieranie ścieżki i tworzenie statystyki wyrazów
                Optional<Path> optionalPath = queue.take();
                if(optionalPath.isPresent())
                {
                    Path path = optionalPath.get();

                    try{
                        Map<String, Long> wordStats = getLinkedCountedWord(path, statistics);
                        System.out.println("Statystyka wyrazów w pliku " + path.getFileName() + ":");
                        if (wordStats.isEmpty()) {
                            System.out.println("  Brak słów w pliku lub wszystkie znaki zostały odfiltrowane");
                        } else {
                            System.out.println("Konsument " + name + " - statystyka dla " + path.getFileName() + ":");
                            wordStats.forEach((word, count) ->
                                    System.out.println("  " + word + " = " + count)
                            );
                        }
                    } catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
              } catch (InterruptedException e)
              {
                  info = String.format("Oczekiwanie konsumeta %s na nowy element z kolejki przerwane!", name);
                    System.out.println(info);
                    Thread.currentThread().interrupt();
              }
          }
          info = String.format("Konsument %s zakończył pracę!", name);
            System.out.println(info);
        };
        for (int i = 0; i < howMuchproducents; i++) {
            Future<?> future = executor.submit(producent);
            producentFuture.add(future);
        }
        for (int i = 0; i < howMuchcustomers; i++) {
            executor.submit(consument);
        }
    }

    private Map<String, Long> getLinkedCountedWord(Path path, int wordsLimit)
    {
        try(BufferedReader reader = Files.newBufferedReader(path))
        {
            return reader.lines()
                    .flatMap(line -> Arrays.stream(line.trim().split("\\s+")))
                    .map(word -> word.replaceAll("[^a-zA-ZąćęłńóśźżĄĆĘŁŃÓŚŹŻ]", ""))
                    .filter(word -> !word.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(wordsLimit)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (k,v) -> {throw new IllegalStateException(String.format ("Błąd! Duplikat klucza %s", k)); },
                            LinkedHashMap::new
                    ));
        } catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }


}
