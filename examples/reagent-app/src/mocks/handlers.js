import { http, HttpResponse, delay } from "msw";

// ---------------------------------------------------------------------------
// In-memory database
// ---------------------------------------------------------------------------

let books = {
  1: { id: 1, title: "Dune", author: "Frank Herbert" },
  2: { id: 2, title: "Neuromancer", author: "William Gibson" },
  3: { id: 3, title: "Snow Crash", author: "Neal Stephenson" },
};

let nextId = 4;

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

export const handlers = [
  // GET /api/books — list all books
  http.get("/api/books", async () => {
    await delay(400);
    const list = Object.values(books).sort((a, b) => a.id - b.id);
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
