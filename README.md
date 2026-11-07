This is a [Kobweb](https://github.com/varabyte/kobweb) project instantiated from the `examples/todo` template.

The purpose of this project is to showcase a minimal app, demonstrating:

* a simple, reactive, single-page web app, making use of both Silk UI and Compose for Web
* API endpoints (e.g. for adding, removing, and fetching items)
* how to send types between client and server (see `Spec` which has text and an ID value, and which gets serialized
  and sent over the wire)

I'd like to give credit to https://blog.upstash.com/nextjs-todo for sharing the Next.js version.

## Run

To run the sample, simply enter the following commands in the terminal:

```bash
$ cd site
$ kobweb run
```

and open [http://localhost:8080](http://localhost:8080) with your browser to see the result.
