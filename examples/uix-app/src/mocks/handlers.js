import { http, HttpResponse, delay } from "msw";

// ---------------------------------------------------------------------------
// In-memory database
// ---------------------------------------------------------------------------

let books = {
  1:  { id: 1,  title: "Dune",                       author: "Frank Herbert" },
  2:  { id: 2,  title: "Neuromancer",                 author: "William Gibson" },
  3:  { id: 3,  title: "Snow Crash",                  author: "Neal Stephenson" },
  4:  { id: 4,  title: "The Left Hand of Darkness",   author: "Ursula K. Le Guin" },
  5:  { id: 5,  title: "Foundation",                  author: "Isaac Asimov" },
  6:  { id: 6,  title: "Hyperion",                    author: "Dan Simmons" },
  7:  { id: 7,  title: "The Dispossessed",            author: "Ursula K. Le Guin" },
  8:  { id: 8,  title: "Brave New World",             author: "Aldous Huxley" },
  9:  { id: 9,  title: "1984",                        author: "George Orwell" },
  10: { id: 10, title: "Fahrenheit 451",              author: "Ray Bradbury" },
  11: { id: 11, title: "Do Androids Dream of Electric Sheep?", author: "Philip K. Dick" },
  12: { id: 12, title: "The Stars My Destination",    author: "Alfred Bester" },
};

let nextId = 13;

// Simulated server start time
const serverStartTime = Date.now();
let requestCount = 0;

// Todos (for optimistic updates demo)
let todos = {
  1: { id: 1, text: "Learn re-frame-query", done: true },
  2: { id: 2, text: "Build optimistic updates", done: false },
  3: { id: 3, text: "Write Playwright tests", done: false },
  4: { id: 4, text: "Ship it 🚀", done: false },
};

// Feed posts (for infinite scroll demo) — per-user feeds
const feedsByUser = {
  alex: [],
  maria: [],
};
for (let i = 1; i <= 35; i++) {
  feedsByUser.alex.push({
    id: i,
    title: `Alex's Post #${i}`,
    body: `Alex writes about topic ${i}. This is an interesting post.`,
    author: "Alex",
    created_at: new Date(Date.now() - (35 - i) * 3600000).toLocaleString(),
  });
}
for (let i = 1; i <= 25; i++) {
  feedsByUser.maria.push({
    id: 1000 + i,
    title: `Maria's Post #${i}`,
    body: `Maria shares her thoughts on subject ${i}.`,
    author: "Maria",
    created_at: new Date(Date.now() - (25 - i) * 3600000).toLocaleString(),
  });
}
let feedNextId = 2000;

// Current user (for dependent queries demo)
const currentUser = {
  id: 42,
  name: "Alice Hacker",
  email: "alice@example.com",
};

// User favorites (for dependent queries demo)
const userFavorites = {
  42: [
    { id: 1, title: "Dune" },
    { id: 5, title: "Foundation" },
    { id: 9, title: "1984" },
  ],
};

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

export const handlers = [
  // GET /api/books — list all books
  http.get("/api/books", async ({ request }) => {
    await delay(400);
    requestCount++;
    const url = new URL(request.url);
    const page = parseInt(url.searchParams.get("page"), 10);
    const perPage = parseInt(url.searchParams.get("per_page"), 10);

    const list = Object.values(books).sort((a, b) => a.id - b.id);

    // If pagination params present, return paginated response
    if (page && perPage) {
      const start = (page - 1) * perPage;
      const items = list.slice(start, start + perPage);
      const totalPages = Math.ceil(list.length / perPage);
      return HttpResponse.json({
        items,
        page,
        per_page: perPage,
        total: list.length,
        total_pages: totalPages,
      });
    }

    // Otherwise return plain array (backwards compatible)
    return HttpResponse.json(list);
  }),

  // GET /api/books/:id — single book
  http.get("/api/books/:id", async ({ params }) => {
    await delay(300);
    requestCount++;
    const book = books[Number(params.id)];
    if (!book) {
      return HttpResponse.json(
        { message: `Book ${params.id} not found` },
        { status: 404 }
      );
    }
    return HttpResponse.json(book);
  }),

  // POST /api/books — create a new book
  http.post("/api/books", async ({ request }) => {
    await delay(400);
    requestCount++;
    const body = await request.json();
    const id = nextId++;
    const book = { id, title: body.title, author: body.author };
    books[id] = book;
    return HttpResponse.json(book, { status: 201 });
  }),

  // PUT /api/books/:id — update a book
  http.put("/api/books/:id", async ({ params, request }) => {
    await delay(300);
    requestCount++;
    const id = Number(params.id);
    const existing = books[id];
    if (!existing) {
      return HttpResponse.json(
        { message: `Book ${id} not found` },
        { status: 404 }
      );
    }
    const body = await request.json();
    const updated = { ...existing, ...body, id };
    books[id] = updated;
    return HttpResponse.json(updated);
  }),

  // DELETE /api/books/:id — delete a book
  http.delete("/api/books/:id", async ({ params }) => {
    await delay(300);
    requestCount++;
    const id = Number(params.id);
    if (!books[id]) {
      return HttpResponse.json(
        { message: `Book ${id} not found` },
        { status: 404 }
      );
    }
    delete books[id];
    return HttpResponse.json({ id, deleted: true });
  }),

  // -------------------------------------------------------------------------
  // Polling demo
  // -------------------------------------------------------------------------

  // GET /api/server-stats — returns live server stats (changes every call)
  http.get("/api/server-stats", async () => {
    await delay(150);
    requestCount++;
    const uptimeMs = Date.now() - serverStartTime;
    const seconds = Math.floor(uptimeMs / 1000);
    const minutes = Math.floor(seconds / 60);
    const uptime = minutes > 0
      ? `${minutes}m ${seconds % 60}s`
      : `${seconds}s`;
    return HttpResponse.json({
      uptime,
      request_count: requestCount,
      server_time: new Date().toLocaleTimeString(),
      active_books: Object.keys(books).length,
    });
  }),

  // -------------------------------------------------------------------------
  // Dependent queries demo
  // -------------------------------------------------------------------------

  // GET /api/me — returns the current user
  http.get("/api/me", async () => {
    await delay(500);
    requestCount++;
    return HttpResponse.json(currentUser);
  }),

  // GET /api/users/:id/favorites — returns user's favorite books
  http.get("/api/users/:id/favorites", async ({ params }) => {
    await delay(400);
    requestCount++;
    const userId = Number(params.id);
    const favorites = userFavorites[userId] || [];
    return HttpResponse.json(favorites);
  }),

  // -------------------------------------------------------------------------
  // Optimistic updates demo
  // -------------------------------------------------------------------------

  // GET /api/todos — list all todos
  http.get("/api/todos", async () => {
    await delay(300);
    requestCount++;
    return HttpResponse.json(Object.values(todos).sort((a, b) => a.id - b.id));
  }),

  // PUT /api/todos/:id — toggle a todo (can be configured to fail)
  http.put("/api/todos/:id", async ({ params, request }) => {
    await delay(800); // Slow on purpose — makes optimistic update visible
    requestCount++;
    const id = Number(params.id);
    const todo = todos[id];
    if (!todo) {
      return HttpResponse.json({ message: `Todo ${id} not found` }, { status: 404 });
    }
    const body = await request.json();
    // If fail_mode is set, randomly fail ~50% of requests
    if (body.fail_mode && Math.random() > 0.5) {
      return HttpResponse.json(
        { message: "Simulated server error (fail mode)" },
        { status: 500 }
      );
    }
    const updated = { ...todo, ...body, id };
    delete updated.fail_mode;
    todos[id] = updated;
    return HttpResponse.json(updated);
  }),

  // -------------------------------------------------------------------------
  // Infinite scroll demo
  // -------------------------------------------------------------------------

  // GET /api/feed?user=X&cursor=Y&limit=Z — cursor-based paginated feed per user
  http.get("/api/feed", async ({ request }) => {
    await delay(400);
    requestCount++;
    const url = new URL(request.url);
    const user = url.searchParams.get("user") || "alex";
    const cursor = parseInt(url.searchParams.get("cursor") || "0", 10);
    const limit = parseInt(url.searchParams.get("limit") || "10", 10);

    const posts = feedsByUser[user] || [];
    const sorted = [...posts].sort((a, b) => b.id - a.id); // newest first
    const startIdx = cursor;
    const items = sorted.slice(startIdx, startIdx + limit);
    const nextCursor = startIdx + limit < sorted.length ? startIdx + limit : null;

    return HttpResponse.json({
      items,
      next_cursor: nextCursor,
      total: sorted.length,
      user,
    });
  }),

  // POST /api/feed — add a new post to a user's feed
  http.post("/api/feed", async ({ request }) => {
    await delay(300);
    requestCount++;
    const body = await request.json();
    const user = body.user || "alex";
    const post = {
      id: feedNextId++,
      title: body.title,
      body: body.body || "A new post just added!",
      author: user.charAt(0).toUpperCase() + user.slice(1),
      created_at: new Date().toLocaleString(),
    };
    if (!feedsByUser[user]) feedsByUser[user] = [];
    feedsByUser[user].push(post);
    return HttpResponse.json(post, { status: 201 });
  }),
];
