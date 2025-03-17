import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CosineSimilarity {
    public static double cosineSimilarity(Map<String, Long> vector1, Map<String, Long> vector2)
    {
        Set<String> allWords = new HashSet<>();
        allWords.addAll(vector1.keySet());
        allWords.addAll(vector2.keySet());

        double dotProduct = 0.0;
        for (String word : allWords) {
            long v1 = vector1.getOrDefault(word, 0L);
            long v2 = vector2.getOrDefault(word, 0L);
            dotProduct += v1 * v2;
        }
        double magnitude1 = 0.0;
        for (Long count : vector1.values()) {
            magnitude1 += count * count;
        }
        magnitude1 = Math.sqrt(magnitude1);

        double magnitude2 = 0.0;
        for (Long count : vector2.values()) {
            magnitude2 += count * count;
        }
        magnitude2 = Math.sqrt(magnitude2);

        if (magnitude1 == 0 || magnitude2 == 0) {
            return 0.0;
        }

        return dotProduct / (magnitude1 * magnitude2);
    }

    public static class SimilarityResults implements Comparable<SimilarityResults>
    {
        private final String documentPath;
        private final double similarityScore;

        public SimilarityResults(String documentPath, double similarityScore)
        {
            this.documentPath = documentPath;
            this.similarityScore = similarityScore;
        }

        @Override
        public int compareTo(SimilarityResults other)
        {
            return Double.compare(other.similarityScore, similarityScore);
        }
        @Override
        public String toString()
        {
            return String.format("%s - Podobieństwo: %.4f", documentPath, similarityScore);
        }
    }
}
