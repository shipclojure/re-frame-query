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

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

export const handlers = [
  // GET /api/books — list all books
  http.get("/api/books", async ({ request }) => {
    await delay(400);
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
    const body = await request.json();
    const id = nextId++;
    const book = { id, title: body.title, author: body.author };
    books[id] = book;
    return HttpResponse.json(book, { status: 201 });
  }),

  // PUT /api/books/:id — update a book
  http.put("/api/books/:id", async ({ params, request }) => {
    await delay(300);
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
];
