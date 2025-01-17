import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;

enum CacheLevel {
    L1,
    L2,
    PRIMARY_STORE
}

enum SearchType {
    TITLE,
    GENRE,
    YEAR
}

class Movie {
    private final String id;
    private final String title;
    private final String genre;
    private final int year;
    private final double rating;

    public Movie(String id, String title, String genre, int year, double rating) {
        this.id = id;
        this.title = title;
        this.genre = genre;
        this.year = year;
        this.rating = rating;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getGenre() { return genre; }
    public int getYear() { return year; }
    public double getRating() { return rating; }
}

class User {
    private final String id;
    private final String name;
    private final String preferredGenre;

    public User(String id, String name, String preferredGenre) {
        this.id = id;
        this.name = name;
        this.preferredGenre = preferredGenre;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getPreferredGenre() { return preferredGenre; }
}

class SearchResult {
    private final Movie movie;
    private final CacheLevel foundIn;

    public SearchResult(Movie movie, CacheLevel foundIn) {
        this.movie = movie;
        this.foundIn = foundIn;
    }

    @Override
    public String toString() {
        return String.format("%s (Found in %s)", movie.getTitle(), foundIn);
    }
}

class CacheEntry {
    private final String searchKey;
    private final List<Movie> results;
    private int frequency;
    private long lastAccessed;

    public CacheEntry(String searchKey, List<Movie> results) {
        this.searchKey = searchKey;
        this.results = new ArrayList<>(results);
        this.frequency = 1;
        this.lastAccessed = System.currentTimeMillis();
    }

    public void incrementFrequency() {
        this.frequency++;
        this.lastAccessed = System.currentTimeMillis();
    }

    public String getSearchKey() { return searchKey; }
    public List<Movie> getResults() { return new ArrayList<>(results); }
    public int getFrequency() { return frequency; }
    public long getLastAccessed() { return lastAccessed; }
}

class L1Cache {
    private final Map<String, Map<String, CacheEntry>> userCache;
    private final int maxEntriesPerUser;

    public L1Cache(int maxEntriesPerUser) {
        this.userCache = new HashMap<>();
        this.maxEntriesPerUser = maxEntriesPerUser;
    }

    public List<Movie> get(String userId, String searchKey) {
        Map<String, CacheEntry> cache = userCache.get(userId);
        if (cache != null && cache.containsKey(searchKey)) {
            CacheEntry entry = cache.get(searchKey);
            entry.incrementFrequency();
            return entry.getResults();
        }
        return null;
    }

    public void put(String userId, String searchKey, List<Movie> results) {
        userCache.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        Map<String, CacheEntry> cache = userCache.get(userId);

        if (cache.size() >= maxEntriesPerUser) {
            String lruKey = cache.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().getLastAccessed()))
                .map(Map.Entry::getKey)
                .orElse(null);
            if (lruKey != null) {
                cache.remove(lruKey);
            }
        }

        cache.put(searchKey, new CacheEntry(searchKey, results));
    }

    public void clear() {
        userCache.clear();
    }
}

class L2Cache {
    private final Map<String, CacheEntry> globalCache;
    private final int maxEntries;

    public L2Cache(int maxEntries) {
        this.globalCache = new HashMap<>();
        this.maxEntries = maxEntries;
    }

    public List<Movie> get(String searchKey) {
        CacheEntry entry = globalCache.get(searchKey);
        if (entry != null) {
            entry.incrementFrequency();
            return entry.getResults();
        }
        return null;
    }

    public void put(String searchKey, List<Movie> results) {
        if (globalCache.size() >= maxEntries) {

            String lfuKey = globalCache.entrySet().stream()
                .min(Comparator.comparingInt(e -> e.getValue().getFrequency()))
                .map(Map.Entry::getKey)
                .orElse(null);
            if (lfuKey != null) {
                globalCache.remove(lfuKey);
            }
        }

        globalCache.put(searchKey, new CacheEntry(searchKey, results));
    }

    public void clear() {
        globalCache.clear();
    }
}

class ZipReelService {
    private final Map<String, Movie> movies;
    private final Map<String, User> users;
    private final L1Cache l1Cache;
    private final L2Cache l2Cache;
    private final CacheStats cacheStats;

    public ZipReelService() {
        this.movies = new HashMap<>();
        this.users = new HashMap<>();
        this.l1Cache = new L1Cache(5);
        this.l2Cache = new L2Cache(20);
        this.cacheStats = new CacheStats();
    }

    public void addMovie(String id, String title, String genre, int year, double rating) {
        if (movies.containsKey(id)) {
            throw new IllegalArgumentException("Movie with ID " + id + " already exists");
        }
        movies.put(id, new Movie(id, title, genre, year, rating));
        System.out.println("Movie '" + title + "' added successfully");
    }

    public void addUser(String id, String name, String preferredGenre) {
        if (users.containsKey(id)) {
            throw new IllegalArgumentException("User with ID " + id + " already exists");
        }
        users.put(id, new User(id, name, preferredGenre));
        System.out.println("User '" + name + "' added successfully");
    }

    public List<SearchResult> search(String userId, SearchType searchType, String searchValue) {
        if (!users.containsKey(userId)) {
            throw new IllegalArgumentException("User not found");
        }

        String cacheKey = searchType + ":" + searchValue;
        List<Movie> results;
        CacheLevel foundIn;

        results = l1Cache.get(userId, cacheKey);
        if (results != null) {
            cacheStats.incrementL1Hits();
            foundIn = CacheLevel.L1;
        } else {
            results = l2Cache.get(cacheKey);
            if (results != null) {
                cacheStats.incrementL2Hits();
                foundIn = CacheLevel.L2;
                l1Cache.put(userId, cacheKey, results);
            } else {
                cacheStats.incrementPrimaryStoreHits();
                foundIn = CacheLevel.PRIMARY_STORE;
                results = searchInPrimaryStore(searchType, searchValue);
                l1Cache.put(userId, cacheKey, results);
                l2Cache.put(cacheKey, results);
            }
        }

        cacheStats.incrementTotalSearches();
        return results.stream()
            .map(movie -> new SearchResult(movie, foundIn))
            .collect(Collectors.toList());
    }

    private List<Movie> searchInPrimaryStore(SearchType searchType, String searchValue) {
        return movies.values().stream()
            .filter(movie -> {
                switch (searchType) {
                    case TITLE:
                        return movie.getTitle().equals(searchValue);
                    case GENRE:
                        return movie.getGenre().equals(searchValue);
                    case YEAR:
                        return String.valueOf(movie.getYear()).equals(searchValue);
                    default:
                        return false;
                }
            })
            .collect(Collectors.toList());
    }

    public List<SearchResult> searchMulti(String userId, String genre, int year, double minRating) {
        if (!users.containsKey(userId)) {
            throw new IllegalArgumentException("User not found");
        }

        String cacheKey = String.format("MULTI:%s:%d:%.1f", genre, year, minRating);
        List<Movie> results;
        CacheLevel foundIn;

        results = l1Cache.get(userId, cacheKey);
        if (results != null) {
            cacheStats.incrementL1Hits();
            foundIn = CacheLevel.L1;
        } else {
            results = l2Cache.get(cacheKey);
            if (results != null) {
                cacheStats.incrementL2Hits();
                foundIn = CacheLevel.L2;
                l1Cache.put(userId, cacheKey, results);
            } else {
                cacheStats.incrementPrimaryStoreHits();
                foundIn = CacheLevel.PRIMARY_STORE;
                results = movies.values().stream()
                    .filter(movie -> 
                        movie.getGenre().equals(genre) &&
                        movie.getYear() == year &&
                        movie.getRating() >= minRating)
                    .collect(Collectors.toList());
                l1Cache.put(userId, cacheKey, results);
                l2Cache.put(cacheKey, results);
            }
        }

        cacheStats.incrementTotalSearches();
        return results.stream()
            .map(movie -> new SearchResult(movie, foundIn))
            .collect(Collectors.toList());
    }

    public void clearCache(CacheLevel level) {
        switch (level) {
            case L1:
                l1Cache.clear();
                break;
            case L2:
                l2Cache.clear();
                break;
            default:
                throw new IllegalArgumentException("Invalid cache level");
        }
        System.out.println(level + " cache cleared successfully");
    }

    public CacheStats getCacheStats() {
        return cacheStats;
    }
}

class CacheStats {
    private int l1Hits;
    private int l2Hits;
    private int primaryStoreHits;
    private int totalSearches;

    public void incrementL1Hits() { l1Hits++; }
    public void incrementL2Hits() { l2Hits++; }
    public void incrementPrimaryStoreHits() { primaryStoreHits++; }
    public void incrementTotalSearches() { totalSearches++; }

    @Override
    public String toString() {
        return String.format(
            "L1 Cache Hits: %d\n" +
            "L2 Cache Hits: %d\n" +
            "Primary Store Hits: %d\n" +
            "Total Searches: %d",
            l1Hits, l2Hits, primaryStoreHits, totalSearches
        );
    }
}

public class Main {
    public static void main(String[] args) {
        ZipReelService service = new ZipReelService();

        try {
            service.addMovie("1", "Inception", "Sci-Fi", 2010, 9.5);
            service.addMovie("2", "The Dark Knight", "Action", 2008, 9.0);
            service.addUser("1", "John", "Action");

            System.out.println("\nFirst search for Sci-Fi movies:");
            List<SearchResult> results = service.search("1", SearchType.GENRE, "Sci-Fi");
            results.forEach(System.out::println);

            System.out.println("\nSecond search for Sci-Fi movies:");
            results = service.search("1", SearchType.GENRE, "Sci-Fi");
            results.forEach(System.out::println);

            service.addUser("2", "Alice", "Sci-Fi");

            System.out.println("\nSearch for Sci-Fi movies from different user:");
            results = service.search("2", SearchType.GENRE, "Sci-Fi");
            results.forEach(System.out::println);

            System.out.println("\nSearching for movies from 2008:");
            results = service.search("1", SearchType.YEAR, "2008");
            results.forEach(System.out::println);

            System.out.println("\nSecond search for 2008 movies:");
            results = service.search("1", SearchType.YEAR, "2008");
            results.forEach(System.out::println);

            System.out.println("\nMulti-criteria search:");
            results = service.searchMulti("1", "Action", 2008, 8.0);
            results.forEach(System.out::println);

            System.out.println("\nSecond multi-criteria search:");
            results = service.searchMulti("1", "Action", 2008, 8.0);
            results.forEach(System.out::println);

            System.out.println("\nCache Statistics:");
            System.out.println(service.getCacheStats());

            service.clearCache(CacheLevel.L1);
            System.out.println("\nAfter clearing L1 cache, searching for Sci-Fi movies:");
            results = service.search("1", SearchType.GENRE, "Sci-Fi");
            results.forEach(System.out::println);

            System.out.println("\nFinal Cache Statistics:");
            System.out.println(service.getCacheStats());

        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}