package es.unizar.webeng.lab3

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.Optional

private val MANAGER_REQUEST_BODY = { name: String ->
    """
    { 
        "role": "Manager", 
        "name": "$name" 
    }
    """
}

private val MANAGER_RESPONSE_BODY = { name: String, id: Int ->
    """
    { 
       "name" : "$name",
       "role" : "Manager",
       "id" : $id
    }
    """
}

@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ControllerTests {
    @Autowired
    private lateinit var mvc: MockMvc

    @MockkBean
    private lateinit var employeeRepository: EmployeeRepository

    @Test
    fun `POST is not safe and not idempotent`() {
        // SETUP
        // Mock save to return different IDs on multiple calls
        // This demonstrates non-idempotency: each POST creates a new resource with a unique ID
        // Also demonstrates non-safety: each POST change the database
        every { employeeRepository.save(any()) } answers {
            Employee("Mary", "Manager", 1)
        } andThenAnswer {
            Employee("Mary", "Manager", 2)
        }

        mvc
            .post("/employees") {
                contentType = MediaType.APPLICATION_JSON
                content = MANAGER_REQUEST_BODY("Mary")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isCreated() }
                header { string("Location", "http://localhost/employees/1") }
                content {
                    contentType(MediaType.APPLICATION_JSON)
                    json(MANAGER_RESPONSE_BODY("Mary", 1))
                }
            }

        mvc
            .post("/employees") {
                contentType = MediaType.APPLICATION_JSON
                content = MANAGER_REQUEST_BODY("Mary")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isCreated() }
                header { string("Location", "http://localhost/employees/2") }
                content {
                    contentType(MediaType.APPLICATION_JSON)
                    json(MANAGER_RESPONSE_BODY("Mary", 2))
                }
            }

        // VERIFY
        // POST is not safe: it modifies state by calling save
        // POST is not idempotent: calling it twice creates two different resources
        verify(exactly = 2) { employeeRepository.save(any()) }
    }

    @Test
    fun `GET is safe and idempotent`() {
        // SETUP
        // Mock repository to return an employee with id = 1
        // GET should always return the same result without modifying state
        every { employeeRepository.findById(1) } answers {
            Optional.of(Employee("Mary", "Manager", 1))
        }
        
        // Mock repository to return empty for id = 2
        // Simulates a 404 case for non-existent employee
        every { employeeRepository.findById(2) } answers {
            Optional.empty()
        }

        mvc.get("/employees/1").andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(MANAGER_RESPONSE_BODY("Mary", 1))
            }
        }

        mvc.get("/employees/1").andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(MANAGER_RESPONSE_BODY("Mary", 1))
            }
        }

        mvc.get("/employees/2").andExpect {
            status { isNotFound() }
        }

        // VERIFY
        // GET is safe: no modification methods (save, deleteById) should be called
        // GET is idempotent: calling it multiple times has the same effect
        verify(exactly = 2) { employeeRepository.findById(1) }
        verify(exactly = 1) { employeeRepository.findById(2) }
        verify(exactly = 0) {
            employeeRepository.save(any())
            employeeRepository.deleteById(any())
            employeeRepository.findAll()
        }
    }

    @Test
    fun `PUT is idempotent but not safe`() {
        // SETUP
        // Mock repository to return empty first (employee doesn't exist), then employee (after creation)
        // This demonstrates PUT's idempotency: first call creates, second call updates with same result
        every { employeeRepository.findById(1) } answers {
            Optional.empty()
        } andThenAnswer {
            Optional.of(Employee("Tom", "Manager", 1))
        }
        
        // Mock save to return employee with specified ID
        // PUT should always use the same ID from the URL
        every { employeeRepository.save(any()) } answers {
            Employee("Tom", "Manager", 1)
        }

        mvc
            .put("/employees/1") {
                contentType = MediaType.APPLICATION_JSON
                content = MANAGER_REQUEST_BODY("Tom")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isCreated() }
                header { string("Content-Location", "http://localhost/employees/1") }
                content {
                    contentType(MediaType.APPLICATION_JSON)
                    json(MANAGER_RESPONSE_BODY("Tom", 1))
                }
            }

        mvc
            .put("/employees/1") {
                contentType = MediaType.APPLICATION_JSON
                content = MANAGER_REQUEST_BODY("Tom")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isOk() }
                header { string("Content-Location", "http://localhost/employees/1") }
                content {
                    contentType(MediaType.APPLICATION_JSON)
                    json(MANAGER_RESPONSE_BODY("Tom", 1))
                }
            }

        // VERIFY
        // PUT is not safe: it modifies state by calling save
        // PUT is idempotent: calling it twice with same data results in same final state
        verify(exactly = 2) { employeeRepository.findById(1) }
        verify(exactly = 2) { employeeRepository.save(any()) }
    }

    @Test
    fun `DELETE is idempotent but not safe`() {
        // SETUP
        // Allow deleteById method to be called
        // DELETE should succeed regardless of whether the resource exists
        justRun { employeeRepository.deleteById(1) }
        
        mvc.delete("/employees/1").andExpect {
            status { isNoContent() }
        }

        mvc.delete("/employees/1").andExpect {
            status { isNoContent() }
        }

        // VERIFY
        // DELETE is not safe: it modifies state by calling deleteById
        // DELETE is idempotent: calling it multiple times has the same effect (resource is gone)
        verify(exactly = 2) { employeeRepository.deleteById(1) }
        verify(exactly = 0) {
            employeeRepository.save(any())
            employeeRepository.findById(any())
            employeeRepository.findAll()
        }
    }
}