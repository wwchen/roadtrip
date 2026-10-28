#!/usr/bin/env node
import { adapterRegistry } from '../etl/adapter-registry.js';
import { runCli } from './run-cli.js';

process.exitCode = await runCli(process.argv.slice(2), {
  adapters: adapterRegistry,
  env: process.env,
  out: (line) => console.log(line),
  err: (line) => console.error(line),
});
