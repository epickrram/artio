/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.system_tests;

import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;

import java.util.List;
import java.util.function.Function;

import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

final class LibraryDriver implements AutoCloseable
{
    private final FakeConnectHandler fakeConnectHandler = new FakeConnectHandler();
    private final FakeOtfAcceptor otfAcceptor = new FakeOtfAcceptor();
    private final FakeHandler handler = new FakeHandler(otfAcceptor);
    private final FixLibrary library;
    private final TestSystem testSystem;

    static LibraryDriver accepting(final TestSystem testSystem)
    {
        return new LibraryDriver(testSystem, SystemTestUtil::acceptingLibraryConfig);
    }

    static LibraryDriver initiating(final int libraryAeronPort, final TestSystem testSystem)
    {
        return new LibraryDriver(testSystem, handler -> initiatingLibraryConfig(libraryAeronPort, handler));
    }

    private LibraryDriver(final TestSystem testSystem, final Function<FakeHandler, LibraryConfiguration> configFactory)
    {
        this.testSystem = testSystem;

        final LibraryConfiguration configuration = configFactory.apply(handler);
        configuration.libraryConnectHandler(fakeConnectHandler);
        library = testSystem.connect(configuration);
    }

    long awaitSessionId()
    {
        return handler.awaitSessionId(this::poll);
    }

    SessionExistsInfo awaitCompleteSessionId()
    {
        return handler.awaitCompleteSessionId(this::poll);
    }

    public void close()
    {
        testSystem.close(library);
    }

    void becomeOnlyLibraryConnectedTo(final FixEngine engine)
    {
        final int ourLibraryId = library.libraryId();
        assertEventuallyTrue(
            () -> ourLibraryId + " has failed to become the only library: " + libraries(engine),
            () ->
            {
                poll();

                final List<LibraryInfo> libraries = libraries(engine);
                return libraries.size() == 2 &&
                       libraryInfoById(libraries, ourLibraryId).isPresent();
            },
            5000,
            () -> {});
    }

    private void poll()
    {
        testSystem.poll();
    }

}
